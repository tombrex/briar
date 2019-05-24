package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Predicate;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.ContactExchangeManager;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.plugin.ConnectionManager;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.record.Record;
import org.briarproject.bramble.api.record.RecordReader;
import org.briarproject.bramble.api.record.RecordReaderFactory;
import org.briarproject.bramble.api.record.RecordWriter;
import org.briarproject.bramble.api.record.RecordWriterFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.logging.Logger;

import javax.inject.Inject;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.contact.RecordTypes.CONTACT_INFO;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.contact.ContactExchangeConstants.ALICE_KEY_LABEL;
import static org.briarproject.bramble.contact.ContactExchangeConstants.ALICE_NONCE_LABEL;
import static org.briarproject.bramble.contact.ContactExchangeConstants.BOB_KEY_LABEL;
import static org.briarproject.bramble.contact.ContactExchangeConstants.BOB_NONCE_LABEL;
import static org.briarproject.bramble.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.briarproject.bramble.contact.ContactExchangeConstants.SIGNING_LABEL;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class ContactExchangeManagerImpl implements ContactExchangeManager {

	private static final Logger LOG =
			getLogger(ContactExchangeManagerImpl.class.getName());

	// Accept records with current protocol version, known record type
	private static final Predicate<Record> ACCEPT = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					isKnownRecordType(r.getRecordType());

	// Ignore records with current protocol version, unknown record type
	private static final Predicate<Record> IGNORE = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType());

	private static boolean isKnownRecordType(byte type) {
		return type == CONTACT_INFO;
	}

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final RecordReaderFactory recordReaderFactory;
	private final RecordWriterFactory recordWriterFactory;
	private final Clock clock;
	private final ConnectionManager connectionManager;
	private final ContactManager contactManager;
	private final IdentityManager identityManager;
	private final TransportPropertyManager transportPropertyManager;
	private final CryptoComponent crypto;
	private final StreamReaderFactory streamReaderFactory;
	private final StreamWriterFactory streamWriterFactory;

	@Inject
	ContactExchangeManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory,
			Clock clock, ConnectionManager connectionManager,
			ContactManager contactManager, IdentityManager identityManager,
			TransportPropertyManager transportPropertyManager,
			CryptoComponent crypto, StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.recordReaderFactory = recordReaderFactory;
		this.recordWriterFactory = recordWriterFactory;
		this.clock = clock;
		this.connectionManager = connectionManager;
		this.contactManager = contactManager;
		this.identityManager = identityManager;
		this.transportPropertyManager = transportPropertyManager;
		this.crypto = crypto;
		this.streamReaderFactory = streamReaderFactory;
		this.streamWriterFactory = streamWriterFactory;
	}

	@Override
	public Author exchangeContacts(TransportId t,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice)
			throws IOException, DbException {
		// Get the transport connection's input and output streams
		InputStream in = conn.getReader().getInputStream();
		OutputStream out = conn.getWriter().getOutputStream();

		// Get the local author and transport properties
		LocalAuthor localAuthor = identityManager.getLocalAuthor();
		Map<TransportId, TransportProperties> localProperties =
				transportPropertyManager.getLocalProperties();

		// Derive the header keys for the transport streams
		SecretKey aliceHeaderKey = crypto.deriveKey(ALICE_KEY_LABEL, masterKey,
				new byte[] {PROTOCOL_VERSION});
		SecretKey bobHeaderKey = crypto.deriveKey(BOB_KEY_LABEL, masterKey,
				new byte[] {PROTOCOL_VERSION});

		// Create the readers
		InputStream streamReader =
				streamReaderFactory.createContactExchangeStreamReader(in,
						alice ? bobHeaderKey : aliceHeaderKey);
		RecordReader recordReader =
				recordReaderFactory.createRecordReader(streamReader);

		// Create the writers
		StreamWriter streamWriter =
				streamWriterFactory.createContactExchangeStreamWriter(out,
						alice ? aliceHeaderKey : bobHeaderKey);
		RecordWriter recordWriter =
				recordWriterFactory
						.createRecordWriter(streamWriter.getOutputStream());

		// Derive the nonces to be signed
		byte[] aliceNonce = crypto.mac(ALICE_NONCE_LABEL, masterKey,
				new byte[] {PROTOCOL_VERSION});
		byte[] bobNonce = crypto.mac(BOB_NONCE_LABEL, masterKey,
				new byte[] {PROTOCOL_VERSION});
		byte[] localNonce = alice ? aliceNonce : bobNonce;
		byte[] remoteNonce = alice ? bobNonce : aliceNonce;

		// Sign the nonce
		byte[] localSignature = sign(localAuthor, localNonce);

		// Exchange contact info
		long localTimestamp = clock.currentTimeMillis();
		ContactInfo remoteInfo;
		if (alice) {
			sendContactInfo(recordWriter, localAuthor, localProperties,
					localSignature, localTimestamp);
			remoteInfo = receiveContactInfo(recordReader);
		} else {
			remoteInfo = receiveContactInfo(recordReader);
			sendContactInfo(recordWriter, localAuthor, localProperties,
					localSignature, localTimestamp);
		}
		// Send EOF on the outgoing stream
		streamWriter.sendEndOfStream();
		// Skip any remaining records from the incoming stream
		recordReader.readRecord(r -> false, IGNORE);

		// Verify the contact's signature
		if (!verify(remoteInfo.author, remoteNonce, remoteInfo.signature)) {
			LOG.warning("Invalid signature");
			throw new FormatException();
		}

		// The agreed timestamp is the minimum of the peers' timestamps
		long timestamp = Math.min(localTimestamp, remoteInfo.timestamp);

		// Add the contact
		ContactId contactId = addContact(remoteInfo.author, localAuthor,
				masterKey, timestamp, alice, remoteInfo.properties);
		// Reuse the connection as a transport connection
		connectionManager.manageOutgoingConnection(contactId, t, conn);
		// Pseudonym exchange succeeded
		LOG.info("Pseudonym exchange succeeded");
		return remoteInfo.author;
	}

	private byte[] sign(LocalAuthor author, byte[] nonce) {
		try {
			return crypto.sign(SIGNING_LABEL, nonce, author.getPrivateKey());
		} catch (GeneralSecurityException e) {
			throw new AssertionError();
		}
	}

	private boolean verify(Author author, byte[] nonce, byte[] signature) {
		try {
			return crypto.verifySignature(signature, SIGNING_LABEL, nonce,
					author.getPublicKey());
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	private void sendContactInfo(RecordWriter recordWriter, Author author,
			Map<TransportId, TransportProperties> properties, byte[] signature,
			long timestamp) throws IOException {
		BdfList authorList = clientHelper.toList(author);
		BdfDictionary props = clientHelper.toDictionary(properties);
		BdfList payload = BdfList.of(authorList, props, signature, timestamp);
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
		LOG.info("Sent contact info");
	}

	private ContactInfo receiveContactInfo(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT, IGNORE);
		if (record == null) throw new EOFException();
		LOG.info("Received contact info");
		BdfList payload = clientHelper.toList(record.getPayload());
		checkSize(payload, 4);
		Author author = clientHelper.parseAndValidateAuthor(payload.getList(0));
		BdfDictionary props = payload.getDictionary(1);
		Map<TransportId, TransportProperties> properties =
				clientHelper.parseAndValidateTransportPropertiesMap(props);
		byte[] signature = payload.getRaw(2);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);
		long timestamp = payload.getLong(3);
		if (timestamp < 0) throw new FormatException();
		return new ContactInfo(author, properties, signature, timestamp);
	}

	private ContactId addContact(Author remoteAuthor, LocalAuthor localAuthor,
			SecretKey masterKey, long timestamp, boolean alice,
			Map<TransportId, TransportProperties> remoteProperties)
			throws DbException {
		return db.transactionWithResult(false, txn -> {
			ContactId contactId = contactManager.addContact(txn, remoteAuthor,
					localAuthor.getId(), masterKey, timestamp, alice,
					true, true);
			transportPropertyManager.addRemoteProperties(txn, contactId,
					remoteProperties);
			return contactId;
		});
	}

	private static class ContactInfo {

		private final Author author;
		private final Map<TransportId, TransportProperties> properties;
		private final byte[] signature;
		private final long timestamp;

		private ContactInfo(Author author,
				Map<TransportId, TransportProperties> properties,
				byte[] signature, long timestamp) {
			this.author = author;
			this.properties = properties;
			this.signature = signature;
			this.timestamp = timestamp;
		}
	}
}
