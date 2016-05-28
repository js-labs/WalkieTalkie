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

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.util.Base64;
import org.jsl.collider.TimerQueue;
import org.jsl.collider.Connector;
import org.jsl.collider.Acceptor;
import org.jsl.collider.Collider;
import org.jsl.collider.Session;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

class Channel
{
    private static final String LOG_TAG = "Channel";

    public interface StateListener
    {
        void onStateChanged( String stateString, boolean registered );
        void onStationListChanged( StationInfo [] stationInfo );
    }

    private static class ServiceInfo
    {
        public NsdServiceInfo nsdServiceInfo;
        public int nsdUpdates;
        public Connector connector;
        public Session session;
        public String stationName;
        public String addr;
        public int state;
        public long ping;
    }

    private static class SessionInfo
    {
        public String stationName;
        public String addr;
        public int state;
        public long ping;
    }

    private final String m_deviceID;
    private String m_stationName;
    private final String m_audioFormat;
    private final Collider m_collider;
    private final NsdManager m_nsdManager;
    private final String m_serviceType;
    private final String m_name;
    private final SessionManager m_sessionManager;
    private final TimerQueue m_timerQueue;
    private final int m_pingInterval;

    private final ReentrantLock m_lock;
    private final TreeMap<String, ServiceInfo> m_serviceInfo; /* Sorting required */
    private final LinkedHashMap<Session, SessionInfo> m_sessions;
    private StateListener m_stateListener;
    private ChannelAcceptor m_acceptor;
    private int m_localPort;
    private RegistrationListener m_registrationListener;
    private String m_serviceName;
    private ResolveListener m_resolveListener;
    private CountDownLatch m_stopLatch;

    private class RegistrationListener implements NsdManager.RegistrationListener
    {
        public void onRegistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            Log.e( LOG_TAG, m_name + ": onRegistrationFailed: " + serviceInfo + " (" + errorCode + ")" );
            Acceptor acceptor;
            m_lock.lock();
            try
            {
                m_registrationListener = null;
                acceptor = m_acceptor;
                m_acceptor = null;

                if (m_stopLatch != null)
                    m_stopLatch.countDown();
            }
            finally
            {
                m_lock.unlock();
            }

            boolean interrupted = false;
            try
            {
                m_collider.removeAcceptor( acceptor );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
                interrupted = true;
            }

            if (interrupted)
                Thread.currentThread().interrupt();
        }

        public void onUnregistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            /* Not expected... */
            Log.e( LOG_TAG, m_name + ": onUnregistrationFailed: " + serviceInfo + " (" + errorCode + ")" );
            if (BuildConfig.DEBUG)
                throw new AssertionError();
        }

        public void onServiceRegistered( NsdServiceInfo nsdServiceInfo )
        {
            /* Service registered now,
             * we use service name to distinguish client from server,
             * so now we can try to connect to the services already discovered.
             */
            Log.i( LOG_TAG, m_name + ": onServiceRegistered: " + nsdServiceInfo );
            Acceptor acceptor;
            m_lock.lock();
            try
            {
                if (m_stopLatch == null)
                {
                    if (m_serviceName != null)
                    {
                        Log.i( LOG_TAG, "Duplicate registration: " + nsdServiceInfo );
                        return;
                    }

                    m_serviceName = nsdServiceInfo.getServiceName();
                    updateStateLocked();

                    for (Map.Entry<String, ServiceInfo> entry : m_serviceInfo.entrySet())
                    {
                        if (m_serviceName.compareTo(entry.getKey()) > 0)
                        {
                            final ServiceInfo serviceInfo = entry.getValue();
                            Log.i( LOG_TAG, m_name + ": resolve service: " + serviceInfo.nsdServiceInfo );
                            serviceInfo.nsdUpdates = 0;
                            m_resolveListener = new ResolveListener( entry.getKey() );
                            m_nsdManager.resolveService( serviceInfo.nsdServiceInfo, m_resolveListener );
                            break;
                        }
                    }

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                    return;
                }
                acceptor = m_acceptor;
                m_acceptor = null;
            }
            finally
            {
                m_lock.unlock();
            }

            /* Appear here only if channel is being stopped. */

            try
            {
                m_collider.removeAcceptor( acceptor );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, m_name + ": " + ex.toString() );
            }
        }

        public void onServiceUnregistered( NsdServiceInfo nsdServiceInfo )
        {
            Log.i( LOG_TAG, m_name + ": onServiceUnregistered: " + nsdServiceInfo );
            Acceptor acceptor;
            m_lock.lock();
            try
            {
                acceptor = m_acceptor;
                m_registrationListener = null;
                m_acceptor = null;
            }
            finally
            {
                m_lock.unlock();
            }

            try
            {
                m_collider.removeAcceptor( acceptor );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, m_name + ": " + ex.toString() );
            }

            /* Service will be unregistered on stop,
             * we expect m_stopLatch will not be null.
             */
            m_stopLatch.countDown();
        }
    }

    private void resolveNextLocked( String skipServiceName )
    {
        for (Map.Entry<String, ServiceInfo> entry : m_serviceInfo.entrySet())
        {
            final String serviceName = entry.getKey();
            if (!serviceName.equals(skipServiceName) &&
                (m_serviceName.compareTo(serviceName) > 0))
            {
                final ServiceInfo serviceInfo = entry.getValue();
                if ((serviceInfo.nsdUpdates > 0) &&
                    (serviceInfo.connector == null) &&
                    (serviceInfo.session == null))
                {
                    Log.i( LOG_TAG, m_name + ": resolveNextLocked: " + serviceInfo.nsdServiceInfo );
                    serviceInfo.nsdUpdates = 0;
                    m_resolveListener = new ResolveListener( serviceName );
                    m_nsdManager.resolveService( serviceInfo.nsdServiceInfo, m_resolveListener );
                }
            }
        }
    }

    private class ResolveListener implements NsdManager.ResolveListener
    {
        private final String m_serviceName;

        public ResolveListener( String serviceName )
        {
            m_serviceName = serviceName;
        }

        public String getServiceName()
        {
            return m_serviceName;
        }

        public void onResolveFailed( NsdServiceInfo nsdServiceInfo, int errorCode )
        {
            Log.i( LOG_TAG, m_name + ": onResolveFailed: " + nsdServiceInfo + " errorCode=" + errorCode );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( m_serviceName );
                if (serviceInfo != null)
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.connector != null) || (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    if (m_stopLatch != null)
                    {
                        m_serviceInfo.remove( m_serviceName );
                        m_registrationListener = null;
                        m_stopLatch.countDown();
                    }
                    else if (serviceInfo.nsdServiceInfo == null)
                    {
                        /* Service lost while being resolved, let's remove record. */
                        m_serviceInfo.remove( m_serviceName );
                    }
                    else
                    {
                        m_resolveListener = null;
                        resolveNextLocked( m_serviceName );
                    }
                }
                else
                    Log.e( LOG_TAG, m_name + ": internal error: service info not found [" + m_serviceName + "]" );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceResolved( NsdServiceInfo nsdServiceInfo )
        {
            Log.i( LOG_TAG, m_name + ": onServiceResolved: " + nsdServiceInfo );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( m_serviceName );
                if (serviceInfo != null)
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.connector != null) || (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    m_resolveListener = null;

                    if (m_stopLatch != null)
                    {
                        m_serviceInfo.remove( m_serviceName );
                        m_stopLatch.countDown();
                    }
                    else
                    {
                        final InetSocketAddress addr = new InetSocketAddress( nsdServiceInfo.getHost(), nsdServiceInfo.getPort() );
                        serviceInfo.connector = new ChannelConnector( addr, m_serviceName );
                        m_collider.addConnector( serviceInfo.connector );

                        resolveNextLocked( m_serviceName );
                    }
                }
                else
                    Log.w( LOG_TAG, m_name + ": internal error: service info not found [" + m_serviceName + "]" );
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    private class ChannelAcceptor extends Acceptor
    {
        public Session.Listener createSessionListener( Session session )
        {
            Log.i( LOG_TAG, m_name + ": " + session.getRemoteAddress() + ": session accepted" );
            m_lock.lock();
            try
            {
                if (m_stopLatch == null)
                {
                    final SessionInfo sessionInfo = new SessionInfo();
                    m_sessions.put( session, sessionInfo );
                    return new HandshakeServerSession(
                            m_audioFormat, m_stationName, Channel.this, session, m_sessionManager, m_timerQueue, m_pingInterval );
                }
                /* else channel is being stopped, just skip a new income connection. */
            }
            finally
            {
                m_lock.unlock();
            }
            return null;
        }

        public void onAcceptorStarted( Collider collider, int localPort )
        {
            Log.i( LOG_TAG, m_name + ": acceptor started: " + localPort );
            m_lock.lock();
            try
            {
                if (m_stopLatch == null)
                {
                    m_localPort = localPort;
                    if (m_stateListener != null)
                        updateStateLocked();

                    /* Android NSD implementation is very unstable when services
                     * registers with the same name. Will use "CHANNEL_NAME:DEVICE_ID:".
                     */
                    final NsdServiceInfo serviceInfo = new NsdServiceInfo();
                    final String serviceName =
                            Base64.encodeToString(m_name.getBytes(), (Base64.NO_PADDING | Base64.NO_WRAP)) +
                            WalkieService.SERVICE_NAME_SEPARATOR +
                            m_deviceID +
                            WalkieService.SERVICE_NAME_SEPARATOR;
                    serviceInfo.setServiceType( m_serviceType );
                    serviceInfo.setServiceName( serviceName );
                    serviceInfo.setPort( localPort );

                    Log.i( LOG_TAG, m_name + ": register service: " + serviceInfo );
                    m_registrationListener = new RegistrationListener();
                    m_nsdManager.registerService( serviceInfo, NsdManager.PROTOCOL_DNS_SD, m_registrationListener );
                    return;
                }
            }
            finally
            {
                m_lock.unlock();
            }

            boolean interrupted = false;
            try
            {
                m_collider.removeAcceptor( this );
            }
            catch (final InterruptedException ex)
            {
                Log.d( LOG_TAG, ex.toString() );
                interrupted = true;
            }

            m_stopLatch.countDown();

            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private class ChannelConnector extends Connector
    {
        private final String m_serviceName;

        public ChannelConnector( InetSocketAddress addr, String serviceName )
        {
            super( addr );
            m_serviceName = serviceName;
        }

        public Session.Listener createSessionListener( Session session )
        {
            Log.i( LOG_TAG, m_name + ": connected to [" + m_serviceName + "] " + getAddr() );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( m_serviceName );
                if (serviceInfo == null)
                {
                    /* strange, should not happen */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.connector != this) || (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    serviceInfo.connector = null;
                    serviceInfo.session = session;
                    return new HandshakeClientSession(
                            Channel.this, m_audioFormat, m_stationName, m_serviceName, session, m_sessionManager, m_timerQueue, m_pingInterval );
                }
            }
            finally
            {
                m_lock.unlock();
            }
            return null;
        }

        public void onException( IOException ex )
        {
            Log.i( LOG_TAG, m_name + ": exception [" + m_serviceName + "] " + getAddr() + ": " + ex.toString() );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( m_serviceName );
                if (serviceInfo == null)
                {
                    /* strange, should not happen */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.connector != this) || (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    serviceInfo.connector = null;

                    if (m_stopLatch == null)
                    {
                        if (serviceInfo.nsdServiceInfo == null)
                        {
                            /* Service lost, let's remove record */
                            m_serviceInfo.remove( m_serviceName );
                        }
                        else if (serviceInfo.nsdUpdates > 0)
                        {
                            /* NsdServiceInfo updated, let's try to resolve it */
                            if (m_resolveListener == null)
                            {
                                /* let's try to resolve it once more */
                                Log.i( LOG_TAG, m_name + ": onException: " + serviceInfo.nsdServiceInfo );
                                serviceInfo.nsdUpdates = 0;
                                m_resolveListener = new ResolveListener( m_serviceName );
                                m_nsdManager.resolveService( serviceInfo.nsdServiceInfo, m_resolveListener );
                            }
                        }
                    }
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    private StationInfo [] getStationListLocked()
    {
        /* Lock is supposed to be held by current thread. */
        if (BuildConfig.DEBUG)
        {
            if (!m_lock.isHeldByCurrentThread())
                throw new AssertionError();

            if (m_serviceName == null)
                throw new AssertionError();
        }
        else if (m_serviceName == null)
            return new StationInfo[0];

        int sessions = 0;
        for (Map.Entry<String, ServiceInfo> e : m_serviceInfo.entrySet())
        {
            if (m_serviceName.compareTo(e.getKey()) > 0)
            {
                /* Show only services with station name (i.e received HandshakeReply) */
                if (e.getValue().stationName != null)
                    sessions++;
            }
        }

        for (Map.Entry<Session, SessionInfo> e : m_sessions.entrySet())
        {
            if (e.getValue().stationName != null)
                sessions++;
        }

        final StationInfo [] stationInfo = new StationInfo[sessions];
        int idx = 0;
        for (Map.Entry<String, ServiceInfo> e : m_serviceInfo.entrySet())
        {
            if (m_serviceName.compareTo(e.getKey()) > 0)
            {
                if (e.getValue().stationName != null)
                {
                    final ServiceInfo serviceInfo = e.getValue();
                    stationInfo[idx++] = new StationInfo(
                            serviceInfo.stationName,
                            serviceInfo.addr,
                            serviceInfo.state,
                            serviceInfo.ping );
                }
            }
        }

        for (Map.Entry<Session, SessionInfo> e : m_sessions.entrySet())
        {
            final SessionInfo sessionInfo = e.getValue();
            if (sessionInfo.stationName != null)
            {
                stationInfo[idx++] = new StationInfo(
                        sessionInfo.stationName,
                        sessionInfo.addr,
                        sessionInfo.state,
                        sessionInfo.ping );
            }
        }

        return stationInfo;
    }

    private void updateStateLocked()
    {
        final StateListener stateListener = m_stateListener;
        if (stateListener != null)
        {
            boolean registered;
            String str = m_name;
            if (m_localPort != -1)
            {
                str += ": ";
                str += Integer.toString( m_localPort );
            }
            if (m_serviceName != null)
            {
                str += '\n';
                str += m_serviceName;
                registered = true;
            }
            else
                registered = false;
            stateListener.onStateChanged( str, registered );
        }
    }

    public Channel(
            String deviceID,
            String stationName,
            String audioFormat,
            Collider collider,
            NsdManager nsdManager,
            String serviceType,
            String name,
            SessionManager sessionManager,
            TimerQueue timerQueue,
            int pingInterval )
    {
        m_deviceID = deviceID;
        m_stationName = stationName;
        m_audioFormat = audioFormat;
        m_collider = collider;
        m_nsdManager = nsdManager;
        m_serviceType = serviceType;
        m_name = name;
        m_sessionManager = sessionManager;
        m_timerQueue = timerQueue;
        m_pingInterval = pingInterval;
        m_serviceInfo = new TreeMap<String, ServiceInfo>();
        m_sessions = new LinkedHashMap<Session, SessionInfo>();
        m_lock = new ReentrantLock();

        m_acceptor = new ChannelAcceptor();
        m_localPort = -1;
        try
        {
            m_collider.addAcceptor( m_acceptor );
        }
        catch (final IOException ex)
        {
            Log.d( LOG_TAG, ex.toString() );
            m_acceptor = null;
        }
    }

    public void setStateListener( StateListener stateListener )
    {
        m_lock.lock();
        try
        {
            m_stateListener = stateListener;
            if (stateListener != null)
            {
                updateStateLocked();
                if (m_serviceName != null)
                    stateListener.onStationListChanged( getStationListLocked() );
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void onServiceFound( NsdServiceInfo nsdServiceInfo )
    {
        /* Run in the NSD manager thread */
        final String serviceName = nsdServiceInfo.getServiceName();

        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && (m_stopLatch != null))
                throw new AssertionError();

            ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
            if (serviceInfo == null)
            {
                serviceInfo = new ServiceInfo();
                m_serviceInfo.put( serviceName, serviceInfo );
            }
            /* Local services will appear one time for each network interface.
             * else
             * {
             *    if (BuildConfig.DEBUG && (serviceInfo.nsdServiceInfo != null))
             *       throw new AssertionError( "Service already registered: " + nsdServiceInfo );
             * }
             */

            serviceInfo.nsdServiceInfo = nsdServiceInfo;
            serviceInfo.nsdUpdates++;

            if ((m_serviceName != null) &&
                (m_serviceName.compareTo(serviceName) > 0))
            {
                if ((serviceInfo.session == null) &&
                    (serviceInfo.connector == null))
                {
                    if (m_resolveListener == null)
                    {
                        Log.i( LOG_TAG, m_name + ": onServiceFound, resolve: " + nsdServiceInfo );
                        serviceInfo.nsdUpdates = 0;
                        m_resolveListener = new ResolveListener( serviceName );
                        m_nsdManager.resolveService( nsdServiceInfo, m_resolveListener );
                    }
                    else
                    {
                        /* Another service is being resolved right now,
                         * will try to resolve new service when current resolve
                         * operation will finish or fail.
                         */
                        Log.i( LOG_TAG, m_name + ": onServiceFound: " + nsdServiceInfo );
                    }
                }
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void onServiceLost( NsdServiceInfo nsdServiceInfo )
    {
        /* Run in the NSD manager thread */
        final String serviceName = nsdServiceInfo.getServiceName();

        m_lock.lock();
        try
        {
            final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
            if (serviceInfo == null)
            {
                /* Should not happen,
                 * but probably would be better to handle a case.
                 */
                Log.w( LOG_TAG, m_name + ": internal error: service not found: " + nsdServiceInfo );
            }
            else if ((m_serviceName != null) &&
                     (m_serviceName.compareTo(serviceName) > 0))
            {
                /* Had to connect to the service */
                if (((m_resolveListener != null) && m_resolveListener.getServiceName().equals(serviceName)) ||
                    (serviceInfo.connector != null) ||
                    (serviceInfo.session != null))
                {
                    /* There is still some activity with this service,
                     * let's keep it while all tasks will not be done.
                     */
                    serviceInfo.nsdServiceInfo = null;
                }
                else
                {
                    m_serviceInfo.remove( serviceName );

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
            }
            else
            {
                /* Remove it from the m_serviceInfo, but do not update activity view. */
                m_serviceInfo.remove( serviceName );
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void setStationName( Session session, String stationName )
    {
        /* Called by the server session channel
         * when received a login request with a client station name.
         */
        m_lock.lock();
        try
        {
            final SessionInfo sessionInfo = m_sessions.get( session );
            if (sessionInfo != null)
            {
                final String str = sessionInfo.stationName;
                sessionInfo.stationName = stationName;
                if (str == null)
                {
                    sessionInfo.addr = session.getRemoteAddress().toString();
                    sessionInfo.state = 0;
                    sessionInfo.ping = 0;
                }

                final StateListener stateListener = m_stateListener;
                if (stateListener != null)
                    stateListener.onStationListChanged( getStationListLocked() );
            }
            else
            {
                Log.e( LOG_TAG, m_name + ": internal error: session " + session.toString() + " not found" );
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void setStationName( String serviceName, String stationName )
    {
        /* Called by the client session instance
         * when received handshake reply with a server station name.
         */
        m_lock.lock();
        try
        {
            final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
            if (serviceInfo != null)
            {
                serviceInfo.stationName = stationName;
                serviceInfo.addr = serviceInfo.session.getRemoteAddress().toString();
                serviceInfo.state = 0;
                serviceInfo.ping = 0;

                final StateListener stateListener = m_stateListener;
                if (stateListener != null)
                    stateListener.onStationListChanged( getStationListLocked() );
            }
            else
            {
                Log.e( LOG_TAG, m_name + ": internal error: service [" + serviceName + "] not found" );
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void setStationName( String stationName )
    {
        m_lock.lock();
        try
        {
            /* Broadcast new station name to all connected stations */
            m_stationName = stationName;
            final ByteBuffer msg = Protocol.StationName.create( stationName );

            for (Map.Entry<String, ServiceInfo> entry : m_serviceInfo.entrySet())
            {
                final ServiceInfo serviceInfo = entry.getValue();
                final Session session = serviceInfo.session;
                if (session != null)
                    session.sendData( msg.duplicate() );
            }

            for (Session session : m_sessions.keySet())
                session.sendData( msg.duplicate() );
        }
        catch (CharacterCodingException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void setSessionState( String serviceName, Session session, int state )
    {
        m_lock.lock();
        try
        {
            if (serviceName == null)
            {
                /* Session created with acceptor */
                final SessionInfo sessionInfo = m_sessions.get( session );
                if (sessionInfo != null)
                {
                    if (BuildConfig.DEBUG && (sessionInfo.state == state))
                        throw new AssertionError();

                    sessionInfo.state = state;

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
                /* else session can be already closed and removed */
            }
            else
            {
                /* session created with connector */
                final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
                if (serviceInfo != null)
                {
                    if (BuildConfig.DEBUG && (serviceInfo.state == state))
                        throw new AssertionError();

                    serviceInfo.state = state;

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
                /* else session can be already closed and removed */
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void setPing( String serviceName, Session session, long ping )
    {
        m_lock.lock();
        try
        {
            if (serviceName == null)
            {
                /* Session created with acceptor */
                final SessionInfo sessionInfo = m_sessions.get( session );
                if (sessionInfo != null)
                {
                    sessionInfo.ping = ping;

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
                else
                {
                    Log.e( LOG_TAG, m_name + ": internal error: session not found" );
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
            }
            else
            {
                /* session created with connector */
                final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
                if (serviceInfo != null)
                {
                    serviceInfo.ping = ping;

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
                else
                {
                    Log.e( LOG_TAG, m_name + ": internal error: service [" + serviceName + "] not found." );
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void removeSession( String serviceName, Session session )
    {
        m_lock.lock();
        try
        {
            if (serviceName == null)
            {
                /* Session created with acceptor */
                final SessionInfo sessionInfo = m_sessions.remove( session );
                if (sessionInfo == null)
                {
                    Log.e( LOG_TAG, m_name + ": internal error: session " + session.toString() + " not found" );
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
            }
            else
            {
                /* session created with connector */
                final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
                if (serviceInfo == null)
                {
                    Log.e( LOG_TAG, m_name + ": internal error: service session [" + serviceName + "] not found" );
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    if (BuildConfig.DEBUG && (serviceInfo.session != session))
                        throw new AssertionError();

                    if (serviceInfo.nsdServiceInfo == null)
                    {
                        /* Service disappeared earlier,
                         * now session is closed and we can forget it.
                         */
                        m_serviceInfo.remove( serviceName );
                    }
                    else
                    {
                        serviceInfo.session = null;
                        serviceInfo.stationName = null;
                        serviceInfo.addr = null;
                        serviceInfo.state = 0;
                        serviceInfo.ping = 0;
                    }

                    final StateListener stateListener = m_stateListener;
                    if (stateListener != null)
                        stateListener.onStationListChanged( getStationListLocked() );
                }
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void stop( CountDownLatch stopLatch )
    {
        m_lock.lock();
        try
        {
            if (m_stopLatch != null)
            {
                /* Channel is being stopped, should not happen. */
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
            else if (m_acceptor != null)
            {
                m_stopLatch = stopLatch;

                if (m_registrationListener == null)
                {
                    /* Acceptor is not started yet */
                    Log.d( LOG_TAG, m_name + ": wait acceptor" );
                    if (BuildConfig.DEBUG && (m_localPort != -1))
                        throw new AssertionError();
                }
                else
                {
                    Log.d( LOG_TAG, m_name + ": unregister service" );
                    m_nsdManager.unregisterService( m_registrationListener );
                }
            }
            else
            {
                /* m_acceptor == null */
                stopLatch.countDown();
            }

            /* Discovery is stopped now,
             * onServiceFound()/onServiceLost() will not be called any more.
             */
            final Iterator<Map.Entry<String, ServiceInfo>> it = m_serviceInfo.entrySet().iterator();
            while (it.hasNext())
            {
                final Map.Entry<String, ServiceInfo> entry = it.next();
                final String serviceName = entry.getKey();
                final ServiceInfo serviceInfo = entry.getValue();
                if (((m_resolveListener != null) && m_resolveListener.getServiceName().equals(serviceName)) ||
                    (serviceInfo.connector != null) ||
                    (serviceInfo.session != null))
                {
                    serviceInfo.nsdServiceInfo = null;
                }
                else
                    it.remove();
            }

            if (m_resolveListener == null)
                stopLatch.countDown();
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public final String getName()
    {
        return m_name;
    }
}
