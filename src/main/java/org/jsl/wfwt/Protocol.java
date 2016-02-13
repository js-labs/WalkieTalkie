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
import java.security.InvalidParameterException;

public class Protocol
{
    private static final short MSG_HANDSHAKE_REQUEST    = 0x0001;
    private static final short MSG_HANDSHAKE_REPLY_OK   = 0x0002;
    private static final short MSG_HANDSHAKE_REPLY_FAIL = 0x0003;
    private static final short MSG_AUDIO_FRAME          = 0x0004;
    private static final short MSG_PING                 = 0x0005;
    private static final short MSG_PONG                 = 0x0006;

    public static final byte VERSION = 1;

    public static class Message
    {
        /* message size (short, 2 bytes) + message type (short, 2 bytes) */
        public static final short HEADER_SIZE = (2 + 2);

        protected static ByteBuffer init( ByteBuffer byteBuffer, short size, short type )
        {
            byteBuffer.putShort( size );
            byteBuffer.putShort( type );
            return byteBuffer;
        }

        public static ByteBuffer create( short type, short extSize )
        {
            if ((HEADER_SIZE + extSize) > Short.MAX_VALUE)
                throw new InvalidParameterException();
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( HEADER_SIZE + extSize );
            return init( byteBuffer, (short) (HEADER_SIZE + extSize), type );
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
        /* short : protocol version
         * short : audio format length
         * str   : audio format
         * short : station name length
         * short : station name
         */
        public static final short ID = MSG_HANDSHAKE_REQUEST;

        public static ByteBuffer create( String audioFormat, String stationName ) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer audioFormatBB = encoder.encode( CharBuffer.wrap(audioFormat) );
            final ByteBuffer stationNameBB = encoder.encode( CharBuffer.wrap(stationName) );
            final ByteBuffer msg = create( ID, (short) (2 + 2 + audioFormatBB.remaining() + 2 + stationNameBB.remaining()) );
            msg.putShort( VERSION );
            msg.putShort( (short) audioFormatBB.remaining() );
            msg.put( audioFormatBB );
            msg.putShort( (short) stationNameBB.remaining() );
            msg.put( stationNameBB );
            msg.rewind();
            return msg;
        }

        public static short getProtocolVersion( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }

        public static String getAudioFormat( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            final int limit = msg.limit();
            try
            {
                msg.position( pos + Message.HEADER_SIZE + 2 );
                final short audioFormatLength = msg.getShort();
                if (audioFormatLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit( msg.position() + audioFormatLength );
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position( pos );
                msg.limit( limit );
            }
            return ret;
        }

        public static String getStationName( RetainableByteBuffer msg ) throws CharacterCodingException
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

    public static class HandshakeReplyOk extends Message
    {
        /* short : audio format length
         * str   : audio format
         * short : station name length
         * short : station name
         */
        public static final short ID = MSG_HANDSHAKE_REPLY_OK;

        public static ByteBuffer create( String audioFormat, String stationName ) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer audioFormatBB = encoder.encode( CharBuffer.wrap(audioFormat) );
            final ByteBuffer stationNameBB = encoder.encode( CharBuffer.wrap(stationName) );
            final ByteBuffer msg = create( ID, (short) (2 + audioFormatBB.remaining() + 2 + stationNameBB.remaining()) );
            msg.putShort( (short) audioFormatBB.remaining() );
            msg.put( audioFormatBB );
            msg.putShort((short) stationNameBB.remaining());
            msg.put( stationNameBB );
            msg.rewind();
            return msg;
        }

        public static String getAudioFormat( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            final int limit = msg.limit();
            try
            {
                msg.position( pos + Message.HEADER_SIZE );
                final short audioFormatLength = msg.getShort();
                if (audioFormatLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit( msg.position() + audioFormatLength );
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position( pos );
                msg.limit( limit );
            }
            return ret;
        }

        public static String getStationName( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            final int limit = msg.limit();
            try
            {
                final short audioFormatLength = msg.getShort( pos + Message.HEADER_SIZE );
                msg.position( pos + Message.HEADER_SIZE + 2 + audioFormatLength );
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
                msg.limit( limit );
            }
            return ret;
        }
    }

    public static class HandshakeReplyFail extends Message
    {
        /* short : status text length
         * str   : status text
         */
        public static final short ID = MSG_HANDSHAKE_REPLY_FAIL;

        public static ByteBuffer create( String statusText ) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer bb = encoder.encode( CharBuffer.wrap(statusText) );
            final ByteBuffer msg = create( ID, (short) (2 + 2 + bb.remaining()) );
            msg.putShort( (short) bb.remaining() );
            msg.put( bb );
            msg.rewind();
            return msg;
        }

        public static String getStatusText( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position( pos + Message.HEADER_SIZE );
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

    public static class AudioFrame extends Message
    {
        public static final short ID = MSG_AUDIO_FRAME;

        public static int getMessageSize( int audioFrameSize )
        {
            return HEADER_SIZE + audioFrameSize;
        }

        public static ByteBuffer init( ByteBuffer byteBuffer, int frameSize )
        {
            return Message.init( byteBuffer, (short) getMessageSize(frameSize), ID );
        }

        public static RetainableByteBuffer getAudioData( RetainableByteBuffer msg )
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
