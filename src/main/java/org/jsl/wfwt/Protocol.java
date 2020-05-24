/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of WiFi WalkieTalkie application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.wfwt;

import org.jsl.collider.RetainableByteBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.security.InvalidParameterException;

class Protocol
{
    private static final short MSG_HANDSHAKE_REQUEST    = 0x0001;
    private static final short MSG_HANDSHAKE_REPLY_OK   = 0x0002;
    private static final short MSG_HANDSHAKE_REPLY_FAIL = 0x0003;
    private static final short MSG_AUDIO_FRAME          = 0x0004;
    private static final short MSG_PING                 = 0x0005;
    private static final short MSG_PONG                 = 0x0006;
    private static final short MSG_STATION_NAME         = 0x0007;

    static final byte VERSION = 2;
    static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    static class Message
    {
        /* message size (short, 2 bytes) + message type (short, 2 bytes) */
        static final short HEADER_SIZE = (2 + 2);

        static ByteBuffer init(ByteBuffer byteBuffer, short size, short type)
        {
            byteBuffer.putShort(size);
            byteBuffer.putShort(type);
            return byteBuffer;
        }

        static ByteBuffer create(short type, short dataSize)
        {
            if ((HEADER_SIZE + dataSize) > Short.MAX_VALUE)
                throw new InvalidParameterException();
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(HEADER_SIZE + dataSize);
            return init(byteBuffer, (short) (HEADER_SIZE + dataSize), type);
        }

        static RetainableByteBuffer createEx(short type, short dataSize)
        {
            if ((HEADER_SIZE + dataSize) > Short.MAX_VALUE)
                throw new InvalidParameterException();
            final RetainableByteBuffer byteBuffer = RetainableByteBuffer.allocateDirect(HEADER_SIZE + dataSize);
            init(byteBuffer.getNioByteBuffer(), (short) (HEADER_SIZE +dataSize), type);
            return byteBuffer;
        }

        static int getLength(ByteBuffer msg)
        {
            return msg.getShort(msg.position());
        }

        static short getMessageId(RetainableByteBuffer msg)
        {
            return msg.getShort(msg.position() + 2);
        }
    }

    static class HandshakeRequest extends Message
    {
        /* short : protocol version
         * short : audio format length
         * str   : audio format
         * short : station name length
         * short : station name
         */
        static final short ID = MSG_HANDSHAKE_REQUEST;

        static ByteBuffer create(String audioFormat, String stationName) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer audioFormatBB = encoder.encode(CharBuffer.wrap(audioFormat));
            final ByteBuffer stationNameBB = encoder.encode(CharBuffer.wrap(stationName));
            final ByteBuffer msg = create(ID, (short) (2 + 2 + audioFormatBB.remaining() + 2 + stationNameBB.remaining()));
            msg.putShort(VERSION);
            msg.putShort((short) audioFormatBB.remaining());
            msg.put(audioFormatBB);
            msg.putShort((short) stationNameBB.remaining());
            msg.put(stationNameBB);
            msg.rewind();
            return msg;
        }

        static short getProtocolVersion(RetainableByteBuffer msg)
        {
            return msg.getShort(msg.position() + HEADER_SIZE);
        }

        static String getAudioFormat(RetainableByteBuffer msg) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            final int limit = msg.limit();
            try
            {
                msg.position(pos + Message.HEADER_SIZE + 2);
                final short audioFormatLength = msg.getShort();
                if (audioFormatLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit(msg.position() + audioFormatLength);
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position(pos);
                msg.limit(limit);
            }
            return ret;
        }

        static String getStationName(RetainableByteBuffer msg) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                final short audioFormatLength = msg.getShort( pos + Message.HEADER_SIZE + 2 );
                msg.position( pos + Message.HEADER_SIZE + 2 + 2 + audioFormatLength );
                final short stationNameLength = msg.getShort();
                if (stationNameLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit( msg.position() + stationNameLength );
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position( pos );
            }
            return ret;
        }
    }

    static class HandshakeReplyOk extends Message
    {
        /* short : audio format length
         * str   : audio format
         * short : station name length
         * short : station name
         */
        static final short ID = MSG_HANDSHAKE_REPLY_OK;

        static ByteBuffer create(String audioFormat, String stationName) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer audioFormatBB = encoder.encode(CharBuffer.wrap(audioFormat));
            final ByteBuffer stationNameBB = encoder.encode(CharBuffer.wrap(stationName));
            final ByteBuffer msg = create(ID, (short) (2 + audioFormatBB.remaining() + 2 + stationNameBB.remaining()));
            msg.putShort((short) audioFormatBB.remaining());
            msg.put(audioFormatBB);
            msg.putShort((short) stationNameBB.remaining());
            msg.put(stationNameBB);
            msg.rewind();
            return msg;
        }

        static String getAudioFormat( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            final int limit = msg.limit();
            try
            {
                msg.position(pos + Message.HEADER_SIZE);
                final short audioFormatLength = msg.getShort();
                if (audioFormatLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit(msg.position() + audioFormatLength);
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position(pos);
                msg.limit(limit);
            }
            return ret;
        }

        static String getStationName(RetainableByteBuffer msg) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            final int limit = msg.limit();
            try
            {
                final short audioFormatLength = msg.getShort(pos + Message.HEADER_SIZE);
                msg.position(pos + Message.HEADER_SIZE + 2 + audioFormatLength);
                final short stationNameLength = msg.getShort();
                if (stationNameLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit(msg.position() + stationNameLength);
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position(pos);
                msg.limit(limit);
            }
            return ret;
        }
    }

    static class HandshakeReplyFail extends Message
    {
        /* short : status text length
         * str   : status text
         */
        static final short ID = MSG_HANDSHAKE_REPLY_FAIL;

        static ByteBuffer create(String statusText) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer bb = encoder.encode(CharBuffer.wrap(statusText));
            final ByteBuffer msg = create(ID, (short) (2 + 2 + bb.remaining()));
            msg.putShort((short) bb.remaining());
            msg.put(bb);
            msg.rewind();
            return msg;
        }

        static String getStatusText(RetainableByteBuffer msg) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position(pos + Message.HEADER_SIZE);
                final short length = msg.getShort();
                if (length > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position(pos);
            }
            return ret;
        }
    }

    static class AudioFrame extends Message
    {
        static final short ID = MSG_AUDIO_FRAME;

        static int getMessageSize(int audioFrameSize)
        {
            return HEADER_SIZE + audioFrameSize;
        }

        static ByteBuffer init(ByteBuffer byteBuffer, int frameSize)
        {
            return Message.init(byteBuffer, (short) getMessageSize(frameSize), ID);
        }

        static RetainableByteBuffer getAudioData( RetainableByteBuffer msg )
        {
            final int remaining = msg.remaining();
            final short messageLength = msg.getShort(); // skip message length
            if (BuildConfig.DEBUG && (messageLength != remaining))
                throw new InvalidParameterException();
            final short messageID = msg.getShort();
            if (BuildConfig.DEBUG && (messageID != ID))
                throw new AssertionError();
            return msg.slice();
        }
    }

    static class Ping extends Message
    {
        /* int : id */
        static final short ID = MSG_PING;

        static ByteBuffer create(int id)
        {
            final short dataSize = (Integer.SIZE / Byte.SIZE);
            final ByteBuffer msg = create(MSG_PING, dataSize);
            msg.putInt(id);
            msg.rewind();
            return msg;
        }

        static int getId(RetainableByteBuffer msg)
        {
            final int pos = msg.position();
            return msg.getInt(pos + HEADER_SIZE);
        }
    }

    static class Pong extends Message
    {
        /* int : id */
        static final short ID = MSG_PONG;

        static ByteBuffer create(int id)
        {
            final short dataSize = (Integer.SIZE / Byte.SIZE);
            final ByteBuffer msg = create(MSG_PONG, dataSize);
            msg.putInt(id);
            msg.rewind();
            return msg;
        }

        static int getId(RetainableByteBuffer msg)
        {
            final int pos = msg.position();
            return msg.getInt(pos + HEADER_SIZE);
        }
    }

    static class StationName extends Message
    {
        static final short ID = MSG_STATION_NAME;

        static RetainableByteBuffer create(String stationName) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer stationNameBB = encoder.encode( CharBuffer.wrap(stationName) );
            final short extSize = (short) ((Short.SIZE / Byte.SIZE) + stationNameBB.remaining());
            final RetainableByteBuffer msg = createEx( ID, extSize );
            msg.putShort( (short) stationNameBB.remaining() );
            msg.put( stationNameBB );
            msg.rewind();
            return msg;
        }

        static String getStationName(RetainableByteBuffer msg) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position(pos + Message.HEADER_SIZE);
                final short length = msg.getShort();
                if (length > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position(pos);
            }
            return ret;
        }
    }
}
