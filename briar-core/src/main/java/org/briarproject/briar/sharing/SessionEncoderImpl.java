package org.briarproject.briar.sharing;

import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.MessageId;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.data.BdfDictionary.NULL_VALUE;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_INVITE_TIMESTAMP;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_LAST_LOCAL_MESSAGE_ID;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_LAST_REMOTE_MESSAGE_ID;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_LOCAL_TIMESTAMP;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_SESSION_ID;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_SHAREABLE_ID;
import static org.briarproject.briar.sharing.SharingConstants.SESSION_KEY_STATE;

@Immutable
@NotNullByDefault
class SessionEncoderImpl implements SessionEncoder {

	@Inject
	SessionEncoderImpl() {
	}

	@Override
	public BdfDictionary encodeSession(Session s) {
		BdfDictionary d = new BdfDictionary();
		d.put(SESSION_KEY_SESSION_ID, s.getShareableId());
		d.put(SESSION_KEY_SHAREABLE_ID, s.getShareableId());
		MessageId lastLocalMessageId = s.getLastLocalMessageId();
		if (lastLocalMessageId == null)
			d.put(SESSION_KEY_LAST_LOCAL_MESSAGE_ID, NULL_VALUE);
		else d.put(SESSION_KEY_LAST_LOCAL_MESSAGE_ID, lastLocalMessageId);
		MessageId lastRemoteMessageId = s.getLastRemoteMessageId();
		if (lastRemoteMessageId == null)
			d.put(SESSION_KEY_LAST_REMOTE_MESSAGE_ID, NULL_VALUE);
		else d.put(SESSION_KEY_LAST_REMOTE_MESSAGE_ID, lastRemoteMessageId);
		d.put(SESSION_KEY_LOCAL_TIMESTAMP, s.getLocalTimestamp());
		d.put(SESSION_KEY_INVITE_TIMESTAMP, s.getInviteTimestamp());
		d.put(SESSION_KEY_STATE, s.getState().getValue());
		return d;
	}

}
