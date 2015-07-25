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
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

public class Protocol
{
    private static final short MSG_HANDSHAKE_REQUEST = 0x0001;
    private static final short MSG_HANDSHAKE_REPLY   = 0x0002;
    private static final short MSG_AUDIO_FRAME       = 0x0003;
    private static final short MSG_PING              = 0x0004;
    private static final short MSG_PONG              = 0x0005;

    public static final byte VERSION = 1;

    public static final short StatusOk = 0;
    public static final short StatusFail = 1;

    public static class Message
    {
        /* message size (short, 2 bytes) + message type (short, 2 bytes) */
        public static final short HEADER_SIZE = (2 + 2);

        public static ByteBuffer create( short type, short extSize )
        {
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( HEADER_SIZE + extSize );
            byteBuffer.putShort( (short)(HEADER_SIZE + extSize) );
            byteBuffer.putShort( type );
            return byteBuffer;
        }

        public static int getLength( ByteBuffer msg )
        {
            return msg.getShort( msg.position() );
        }

        public static short getID( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + 2 );
        }
    }

    public static class HandshakeRequest extends Message
    {
        public static final short ID = MSG_HANDSHAKE_REQUEST;

        public static ByteBuffer create( String audioFormat ) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer bb = encoder.encode( CharBuffer.wrap(audioFormat) );
            final ByteBuffer msg = create( ID, (short) (2 + 2 + bb.remaining()) );
            msg.putShort( VERSION );
            msg.putShort( (short) bb.remaining() );
            msg.put( bb );
            msg.rewind();
            return msg;
        }

        public static int getLength( ByteBuffer msg )
        {
            /* It would be better to validate the whole message. */
            final int remaining = msg.remaining();
            final int messageLength = Message.getLength(msg);
            if (remaining > (HEADER_SIZE + 2 + 2))
            {
                final int pos = msg.position();
                final short audioFormatLength = msg.getShort( pos + HEADER_SIZE + 2 );
                if (messageLength != (HEADER_SIZE + 4 + audioFormatLength))
                    return -1;
            }
            return messageLength;
        }

        public static short getProtocolVersion( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }

        public static String getAudioFormat( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position( pos + Message.HEADER_SIZE + 2 );
                final short length = msg.getShort();
                if (length > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
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

    public static class HandshakeReply extends Message
    {
        /* short : version
         * short : status
         * short : audio format length
         * str   : audio format
         */
        public static final short ID = MSG_HANDSHAKE_REPLY;

        private static ByteBuffer create( short status, String str ) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer bb = encoder.encode( CharBuffer.wrap(str) );
            final ByteBuffer msg = create( ID, (short) (2 + 2 + 2 + bb.remaining()) );
            msg.putShort( VERSION );
            msg.putShort( status );
            msg.putShort( (short) bb.remaining() );
            msg.put( bb );
            msg.rewind();
            return msg;
        }

        public static ByteBuffer createOk( String audioFormat ) throws CharacterCodingException
        {
            return create( StatusOk, audioFormat );
        }

        public static ByteBuffer createFail( String statusText ) throws CharacterCodingException
        {
            return create( StatusFail, statusText );
        }

        public static short getStatus( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE + 2 );
        }

        /* Returns audio format if status is StatusOk,
         * error message if status is StatusFail
         */
        public static String getString( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position( pos + Message.HEADER_SIZE + 2 + 2 );
                final short length = msg.getShort();
                if (length != 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
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

    public static class AudioFrame extends Message
    {
        public static final short ID = MSG_AUDIO_FRAME;

        public static int getMessageSize( int audioFrameSize )
        {
            return HEADER_SIZE + audioFrameSize;
        }

        public static int getDataOffs()
        {
            return HEADER_SIZE;
        }

        public static ByteBuffer getAudioData( RetainableByteBuffer msg )
        {
            msg.position( msg.position() + HEADER_SIZE );
            return msg.getNioByteBuffer();
        }
    }

    public static class Ping extends Message
    {
        public static final short ID = MSG_PING;
        private static final ByteBuffer s_msg = create_i();

        private static ByteBuffer create_i()
        {
            final ByteBuffer msg = create( ID, (short)0 );
            msg.rewind();
            return msg;
        }

        public static ByteBuffer create()
        {
            return s_msg.duplicate();
        }
    }

    public static class Pong extends Message
    {
        public static final short ID = MSG_PONG;
        private static final ByteBuffer s_msg = create_i();

        private static ByteBuffer create_i()
        {
            final ByteBuffer msg = create( ID, (short)0 );
            msg.rewind();
            return msg;
        }

        public static ByteBuffer create()
        {
            return s_msg.duplicate();
        }
    }
}
