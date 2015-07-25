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
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashSet;

public class SessionManager
{
    private final ReentrantLock m_lock;
    private HashSet<ChannelSession> m_sessions;

    public SessionManager()
    {
        m_lock = new ReentrantLock();
        m_sessions = new HashSet<ChannelSession>();
    }

    public void addSession( ChannelSession session )
    {
        /* Will use copy-on-write pattern for m_sessions,
         * the set will not be changed too often.
         * Lock is still needed for concurrent modifies.
         */
        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && m_sessions.contains(session))
                throw new AssertionError();

            final HashSet<ChannelSession> sessions =
                (m_sessions == null)
                        ? new HashSet<ChannelSession>()
                        : (HashSet<ChannelSession>) m_sessions.clone();
            sessions.add( session );
            m_sessions = sessions;
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void send( RetainableByteBuffer msg )
    {
        for (ChannelSession session : m_sessions)
            session.sendMessage(msg);
    }
}
