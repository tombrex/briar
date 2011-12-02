package net.sf.briar.api.transport;

import java.io.OutputStream;

public interface ConnectionWriterFactory {

	/**
	 * Creates a connection writer for a batch-mode connection or the
	 * initiator's side of a stream-mode connection. The secret is erased
	 * before this method returns.
	 */
	ConnectionWriter createConnectionWriter(OutputStream out, long capacity,
			byte[] secret);

	/**
	 * Creates a connection writer for the responder's side of a stream-mode
	 * connection. The secret is erased before this method returns.
	 */
	ConnectionWriter createConnectionWriter(OutputStream out, long capacity,
			byte[] secret, byte[] tag);
}
