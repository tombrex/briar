package net.sf.briar.plugins.modem;

import net.sf.briar.util.ByteUtils;

class Ack extends Frame {

	static final int LENGTH = 12;

	Ack() {
		super(new byte[LENGTH]);
		buf[0] = (byte) Frame.ACK_FLAG;
	}

	Ack(byte[] buf) {
		super(buf);
		if(buf.length != LENGTH) throw new IllegalArgumentException();
		buf[0] = (byte) Frame.ACK_FLAG;
	}

	int getWindowSize() {
		return ByteUtils.readUint24(buf, 5);
	}

	void setWindowSize(int windowSize) {
		ByteUtils.writeUint24(windowSize, buf, 5);
	}
}
