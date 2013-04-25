package net.sf.briar.plugins.tor;

import static android.content.Context.MODE_PRIVATE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.zip.ZipInputStream;

import net.freehaven.tor.control.EventHandler;
import net.freehaven.tor.control.TorControlConnection;
import net.sf.briar.api.ContactId;
import net.sf.briar.api.TransportConfig;
import net.sf.briar.api.TransportId;
import net.sf.briar.api.TransportProperties;
import net.sf.briar.api.crypto.PseudoRandom;
import net.sf.briar.api.plugins.duplex.DuplexPlugin;
import net.sf.briar.api.plugins.duplex.DuplexPluginCallback;
import net.sf.briar.api.plugins.duplex.DuplexTransportConnection;
import net.sf.briar.util.StringUtils;
import socks.Socks5Proxy;
import socks.SocksSocket;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.FileObserver;

class TorPlugin implements DuplexPlugin, EventHandler {

	static final byte[] TRANSPORT_ID =
			StringUtils.fromHexString("fa866296495c73a52e6a82fd12db6f15"
					+ "47753b5e636bb8b24975780d7d2e3fc2"
					+ "d32a4c480c74de2dc6e3157a632a0287");
	static final TransportId ID = new TransportId(TRANSPORT_ID);

	private static final int SOCKS_PORT = 59050, CONTROL_PORT = 59051;
	private static final int COOKIE_TIMEOUT = 3000; // Milliseconds
	private static final int HOSTNAME_TIMEOUT = 30 * 1000; // Milliseconds
	private static final Logger LOG =
			Logger.getLogger(TorPlugin.class.getName());

	private final Executor pluginExecutor;
	private final Context appContext;
	private final DuplexPluginCallback callback;
	private final long maxLatency, pollingInterval;

	private volatile boolean running = false;
	private volatile Process torProcess = null;
	private volatile ServerSocket socket = null;

	TorPlugin(Executor pluginExecutor, Context appContext,
			DuplexPluginCallback callback, long maxLatency,
			long pollingInterval) {
		this.pluginExecutor = pluginExecutor;
		this.appContext = appContext;
		this.callback = callback;
		this.maxLatency = maxLatency;
		this.pollingInterval = pollingInterval;
	}

	public TransportId getId() {
		return ID;
	}

	public String getName() {
		return "TOR_PLUGIN_NAME";
	}

	public long getMaxLatency() {
		return maxLatency;
	}

	public boolean start() throws IOException {
		Socket s;
		try {
			s = new Socket("127.0.0.1", CONTROL_PORT);
			if(LOG.isLoggable(INFO)) LOG.info("Tor is already running");
		} catch(IOException e) {
			if(!isInstalled() && !install()) {
				if(LOG.isLoggable(INFO)) LOG.info("Could not install Tor");
				return false;
			}
			if(LOG.isLoggable(INFO)) LOG.info("Starting Tor");
			File cookieFile = getCookieFile();
			cookieFile.getParentFile().mkdirs();
			cookieFile.createNewFile();
			CountDownLatch latch = new CountDownLatch(1);
			FileObserver obs = new WriteObserver(cookieFile, latch);
			obs.startWatching();
			String torPath = getTorFile().getAbsolutePath();
			String configPath = getConfigFile().getAbsolutePath();
			String[] command = { torPath, "-f", configPath };
			String home = "HOME=" + getTorDirectory().getAbsolutePath();
			String[] environment = { home };
			File dir = getTorDirectory();
			torProcess = Runtime.getRuntime().exec(command, environment, dir);
			if(LOG.isLoggable(INFO)) {
				Scanner stdout = new Scanner(torProcess.getInputStream());
				while(stdout.hasNextLine()) LOG.info(stdout.nextLine());
				stdout.close();
			}
			try {
				int exit = torProcess.waitFor();
				if(exit != 0) {
					if(LOG.isLoggable(WARNING))
						LOG.warning("Tor exited with value " + exit);
					return false;
				}
				if(!latch.await(COOKIE_TIMEOUT, MILLISECONDS)) {
					if(LOG.isLoggable(WARNING))
						LOG.warning("Auth cookie not created");
					listFiles(getTorDirectory());
					return false;
				}
			} catch(InterruptedException e1) {
				if(LOG.isLoggable(WARNING))
					LOG.warning("Interrupted while starting Tor");
				return false;
			}
			s = new Socket("127.0.0.1", CONTROL_PORT);
		}
		TorControlConnection control = new TorControlConnection(s);
		control.launchThread(true);
		control.authenticate(read(getCookieFile()));
		control.setEventHandler(this);
		control.setEvents(Arrays.asList("NOTICE", "WARN", "ERR"));
		running = true;
		pluginExecutor.execute(new Runnable() {
			public void run() {
				bind();
			}
		});
		return true;
	}

	private boolean isInstalled() {
		return getDoneFile().exists();
	}

	private boolean install() {
		InputStream in = null;
		OutputStream out = null;
		try {
			in = getTorInputStream();
			out = new FileOutputStream(getTorFile());
			copy(in, out);
			in = getGeoIpInputStream();
			out = new FileOutputStream(getGeoIpFile());
			copy(in, out);
			in = getConfigInputStream();
			out = new FileOutputStream(getConfigFile());
			copy(in, out);
			if(!setExecutable(getTorFile())) {
				if(LOG.isLoggable(WARNING))
					LOG.warning("Could not make Tor executable");
				return false;
			}
			File done = getDoneFile();
			done.createNewFile();
			return true;
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			tryToClose(in);
			tryToClose(out);
			return false;
		}
	}

	private InputStream getTorInputStream() throws IOException {
		InputStream in = appContext.getResources().getAssets().open("tor");
		ZipInputStream zin = new ZipInputStream(in);
		if(zin.getNextEntry() == null) throw new IOException();
		return zin;
	}

	private InputStream getGeoIpInputStream() throws IOException {
		InputStream in = appContext.getResources().getAssets().open("geoip");
		ZipInputStream zin = new ZipInputStream(in);
		if(zin.getNextEntry() == null) throw new IOException();
		return zin;
	}

	private InputStream getConfigInputStream() throws IOException {
		return appContext.getResources().getAssets().open("torrc");
	}

	private void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[4096];
		while(true) {
			int read = in.read(buf);
			if(read == -1) break;
			out.write(buf, 0, read);
		}
		in.close();
		out.close();
	}

	@SuppressLint("NewApi")
	private boolean setExecutable(File f) {
		if(Build.VERSION.SDK_INT >= 9) {
			return f.setExecutable(true, true);
		} else {
			String[] command = { "chmod", "700", f.getAbsolutePath() };
			try {
				return Runtime.getRuntime().exec(command).waitFor() == 0;
			} catch(IOException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			} catch(InterruptedException e) {
				if(LOG.isLoggable(WARNING))
					LOG.warning("Interrupted while executing chmod");
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}

	private void tryToClose(InputStream in) {
		try {
			if(in != null) in.close();
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
	}

	private void tryToClose(OutputStream out) {
		try {
			if(out != null) out.close();
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
	}

	private byte[] read(File f) throws IOException {
		byte[] b = new byte[(int) f.length()];
		FileInputStream in = new FileInputStream(f);
		try {
			int offset = 0;
			while(offset < b.length) {
				int read = in.read(b, offset, b.length - offset);
				if(read == -1) throw new EOFException();
				offset += read;
			}
			return b;
		} finally {
			in.close();
		}
	}

	private void bind() {
		String portString = callback.getConfig().get("port");
		int port;
		if(StringUtils.isNullOrEmpty(portString)) port = 0;
		else port = Integer.parseInt(portString);
		ServerSocket ss = null;
		try {
			ss = new ServerSocket();
			ss.bind(new InetSocketAddress("127.0.0.1", port));
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			tryToClose(ss);
		}
		if(!running) {
			tryToClose(ss);
			return;
		}
		socket = ss;
		final String localPort = String.valueOf(ss.getLocalPort());
		TransportConfig c  = new TransportConfig();
		c.put("port", localPort);
		callback.mergeConfig(c);
		pluginExecutor.execute(new Runnable() {
			public void run() {
				publishHiddenService(localPort);
			}
		});
		acceptContactConnections(ss);
	}

	private void tryToClose(ServerSocket ss) {
		try {
			if(ss != null) ss.close();
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
	}

	private void publishHiddenService(final String port) {
		if(!running) return;
		File hostnameFile = getHostnameFile();
		if(!hostnameFile.exists()) {
			if(LOG.isLoggable(INFO)) LOG.info("Creating hidden service");
			try {
				hostnameFile.getParentFile().mkdirs();
				hostnameFile.createNewFile();
				CountDownLatch latch = new CountDownLatch(1);
				FileObserver obs = new WriteObserver(hostnameFile, latch);
				obs.startWatching();
				String dir = getTorDirectory().getAbsolutePath();
				List<String> config = Arrays.asList("HiddenServiceDir " + dir,
						"HiddenServicePort 80 127.0.0.1:" + port);
				// FIXME: Socket isn't closed
				Socket s = new Socket("127.0.0.1", CONTROL_PORT);
				TorControlConnection control = new TorControlConnection(s);
				control.launchThread(true);
				control.authenticate(read(getCookieFile()));
				control.setConf(config);
				control.saveConf();
				if(!latch.await(HOSTNAME_TIMEOUT, MILLISECONDS)) {
					if(LOG.isLoggable(WARNING))
						LOG.warning("Hidden service not created");
					listFiles(getTorDirectory());
					return;
				}
				if(!running) return;
			} catch(IOException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			} catch(InterruptedException e) {
				if(LOG.isLoggable(WARNING))
					LOG.warning("Interrupted while creating hidden service");
			}
		}
		try {
			String hostname = new String(read(hostnameFile), "UTF-8").trim();
			if(LOG.isLoggable(INFO)) LOG.info("Hidden service " + hostname);
			TransportProperties p = new TransportProperties();
			p.put("onion", hostname);
			callback.mergeLocalProperties(p);
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
	}

	private void acceptContactConnections(ServerSocket ss) {
		while(true) {
			Socket s;
			try {
				s = ss.accept();
			} catch(IOException e) {
				// This is expected when the socket is closed
				if(LOG.isLoggable(INFO)) LOG.log(INFO, e.toString(), e);
				tryToClose(ss);
				return;
			}
			if(LOG.isLoggable(INFO)) LOG.info("Connection received");
			callback.incomingConnectionCreated(new TorTransportConnection(s,
					maxLatency));
			if(!running) return;
		}
	}

	public void stop() throws IOException {
		running = false;
		if(socket != null) tryToClose(socket);
		try {
			if(LOG.isLoggable(INFO)) LOG.info("Stopping Tor");
			// FIXME: Socket isn't closed
			Socket s = new Socket("127.0.0.1", CONTROL_PORT);
			TorControlConnection control = new TorControlConnection(s);
			control.launchThread(true);
			control.authenticate(read(getCookieFile()));
			control.shutdownTor("TERM");
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			if(torProcess != null) {
				if(LOG.isLoggable(INFO)) LOG.info("Killing Tor");
				torProcess.destroy();
			}
		}
	}

	public boolean shouldPoll() {
		return true;
	}

	public long getPollingInterval() {
		return pollingInterval;
	}

	public void poll(Collection<ContactId> connected) {
		if(!running) return;
		Map<ContactId, TransportProperties> remote =
				callback.getRemoteProperties();
		for(final ContactId c : remote.keySet()) {
			if(connected.contains(c)) continue;
			pluginExecutor.execute(new Runnable() {
				public void run() {
					connectAndCallBack(c);
				}
			});
		}
	}

	private void connectAndCallBack(ContactId c) {
		DuplexTransportConnection d = createConnection(c);
		if(d != null) callback.outgoingConnectionCreated(c, d);
	}

	public DuplexTransportConnection createConnection(ContactId c) {
		if(!running) return null;
		TransportProperties p = callback.getRemoteProperties().get(c);
		if(p == null) return null;
		String onion = p.get("onion");
		if(StringUtils.isNullOrEmpty(onion)) return null;
		// FIXME: Check that it's an onion hostname
		try {
			if(LOG.isLoggable(INFO)) LOG.info("Connecting to " + onion);
			Socks5Proxy proxy = new Socks5Proxy("127.0.0.1", SOCKS_PORT);
			proxy.resolveAddrLocally(false);
			Socket s = new SocksSocket(proxy, onion, 80);
			if(LOG.isLoggable(INFO)) LOG.info("Connected to " + onion);
			return new TorTransportConnection(s, maxLatency);
		} catch(IOException e) {
			if(LOG.isLoggable(INFO)) LOG.log(INFO, e.toString(), e);
			return null;
		}
	}

	public boolean supportsInvitations() {
		return false;
	}

	public DuplexTransportConnection sendInvitation(PseudoRandom r,
			long timeout) {
		throw new UnsupportedOperationException();
	}

	public DuplexTransportConnection acceptInvitation(PseudoRandom r,
			long timeout) {
		throw new UnsupportedOperationException();
	}

	private File getTorFile() {
		return new File(getTorDirectory(), "tor");
	}

	private File getGeoIpFile() {
		return new File(getTorDirectory(), "geoip");
	}

	private File getConfigFile() {
		return new File(getTorDirectory(), "torrc");
	}

	private File getDoneFile() {
		return new File(getTorDirectory(), "done");
	}

	private File getCookieFile() {
		return new File(getTorDirectory(), ".tor/control_auth_cookie");
	}

	private File getHostnameFile() {
		return new File(getTorDirectory(), "hostname");
	}

	private File getTorDirectory() {
		return appContext.getDir("tor", MODE_PRIVATE);
	}

	private void listFiles(File f) {
		if(f.isDirectory()) for(File f1 : f.listFiles()) listFiles(f1);
		else if(LOG.isLoggable(INFO)) LOG.info(f.getAbsolutePath());
	}

	public void circuitStatus(String status, String circID, String path) {
		if(LOG.isLoggable(INFO)) LOG.info("Circuit status");
	}

	public void streamStatus(String status, String streamID, String target) {
		if(LOG.isLoggable(INFO)) LOG.info("Stream status");
	}

	public void orConnStatus(String status, String orName) {
		if(LOG.isLoggable(INFO)) LOG.info("OR connection status");		
	}

	public void bandwidthUsed(long read, long written) {
		if(LOG.isLoggable(INFO)) LOG.info("Bandwidth used");		
	}

	public void newDescriptors(List<String> orList) {
		if(LOG.isLoggable(INFO)) LOG.info("New descriptors");		
	}

	public void message(String severity, String msg) {
		if(LOG.isLoggable(INFO)) LOG.info("Message: " + severity + " " + msg);		
	}

	public void unrecognized(String type, String msg) {
		if(LOG.isLoggable(INFO)) LOG.info("Unrecognized");		
	}

	private static class WriteObserver extends FileObserver {

		private final CountDownLatch latch;

		private WriteObserver(File file, CountDownLatch latch) {
			super(file.getAbsolutePath(), CLOSE_WRITE);
			this.latch = latch;
		}

		public void onEvent(int event, String path) {
			stopWatching();
			latch.countDown();
		}
	}
}
