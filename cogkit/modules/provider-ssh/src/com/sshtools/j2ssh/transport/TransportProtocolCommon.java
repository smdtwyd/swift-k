/*
 *  Sshtools - Java SSH2 API
 *
 *  Copyright (C) 2002 Lee David Painter.
 *
 *  Written by: 2002 Lee David Painter <lee@sshtools.com>
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2 of
 *  the License, or (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU Library General Public
 *  License along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package com.sshtools.j2ssh.transport;

import com.sshtools.j2ssh.SshException;
import com.sshtools.j2ssh.SshThread;

import com.sshtools.j2ssh.configuration.ConfigurationLoader;
import com.sshtools.j2ssh.configuration.SshConnectionProperties;

import com.sshtools.j2ssh.io.ByteArrayWriter;

import com.sshtools.j2ssh.net.TransportProvider;

import com.sshtools.j2ssh.transport.kex.KeyExchangeException;
import com.sshtools.j2ssh.transport.kex.SshKeyExchange;
import com.sshtools.j2ssh.transport.kex.SshKeyExchangeFactory;

import com.sshtools.j2ssh.util.Hash;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.InterruptedIOException;

import java.math.BigInteger;

//import java.net.SocketTimeoutException;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;


/**
 * DOCUMENT ME!
 *
 * @author $author$
 * @version $Revision: 1.1 $
 */
public abstract class TransportProtocolCommon implements TransportProtocol,
    Runnable {
    // Flag to keep on running
    //private boolean keepRunning = true;

    /** DOCUMENT ME! */
    protected static Log log = LogFactory.getLog(TransportProtocolCommon.class);
    private static int nextThreadNo = 1;

    /** DOCUMENT ME! */
    public final static int EOL_CRLF = 1;

    /** DOCUMENT ME! */
    public final static int EOL_LF = 2;

    /** DOCUMENT ME! */
    public static final String PROTOCOL_VERSION = "2.0";

    /** DOCUMENT ME! */
    public static String SOFTWARE_VERSION_COMMENTS = "http://www.sshtools.com "
        + ConfigurationLoader.getVersionString("J2SSH", "j2ssh.properties");
    private int threadNo = nextThreadNo++;

    /** DOCUMENT ME! */
    protected BigInteger k = null;

    /** DOCUMENT ME! */
    protected Boolean completeOnNewKeys = new Boolean(false);

    /** DOCUMENT ME! */
    protected HostKeyVerification hosts;

    /** DOCUMENT ME! */
    protected Map kexs = new HashMap();
    private boolean sendIgnore = false;

    //protected Map transportMessages = new HashMap();

    /** DOCUMENT ME! */
    protected SshConnectionProperties properties;

    /** DOCUMENT ME! */
    protected SshMessageStore messageStore = new SshMessageStore();

    /** DOCUMENT ME! */
    protected SshMsgKexInit clientKexInit = null;

    /** DOCUMENT ME! */
    protected SshMsgKexInit serverKexInit = null;

    /** DOCUMENT ME! */
    protected String clientIdent = null;

    /** DOCUMENT ME! */
    protected String serverIdent = null;

    /** DOCUMENT ME! */
    protected TransportProtocolAlgorithmSync algorithmsIn;

    /** DOCUMENT ME! */
    protected TransportProtocolAlgorithmSync algorithmsOut;

    /** DOCUMENT ME! */
    protected TransportProtocolState state = new TransportProtocolState();
    private byte[] exchangeHash = null;

    /** DOCUMENT ME! */
    protected byte[] sessionIdentifier = null;

    /** DOCUMENT ME! */
    protected byte[] hostKey = null;

    /** DOCUMENT ME! */
    protected byte[] signature = null;
    private Vector eventHandlers = new Vector();

    // Storage of messages whilst in key exchange
    private List messageStack = new ArrayList();

    // Message notification registry
    private Map messageNotifications = new HashMap();

    // Key exchange lock for accessing the kex init messages
    private Object kexLock = new Object();

    // Object to synchronize key changing
    private Object keyLock = new Object();

    // The connected socket
    //private Socket socket;
    // The underlying transport provider
    TransportProvider provider;

    // The thread object
    private SshThread thread;
    private long kexTimeout = 3600000L;
    private long kexTransferLimit = 1073741824L;
    private long startTime = System.currentTimeMillis();

    // The input stream for recieving data

    /** DOCUMENT ME! */
    protected TransportProtocolInputStream sshIn;

    // The output stream for sending data

    /** DOCUMENT ME! */
    protected TransportProtocolOutputStream sshOut;
    private int remoteEOL = EOL_CRLF;

    //private Map registeredMessages = new HashMap();
    private Vector messageStores = new Vector();

    /**
     * Creates a new TransportProtocolCommon object.
     */
    public TransportProtocolCommon() {
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public int getConnectionId() {
        return threadNo;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public int getRemoteEOL() {
        return remoteEOL;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public TransportProtocolState getState() {
        return state;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public SshConnectionProperties getProperties() {
        return properties;
    }

    /**
     * DOCUMENT ME!
     */
    protected abstract void onDisconnect();

    /**
     * DOCUMENT ME!
     *
     * @param description DOCUMENT ME!
     */
    public void disconnect(String description) {
        if (log.isDebugEnabled()) {
            log.debug("Disconnect: " + description);
        }

        try {
            state.setDisconnectReason(description);

            // Send the disconnect message automatically
            sendDisconnect(SshMsgDisconnect.BY_APPLICATION, description);
        } catch (Exception e) {
            log.warn("Failed to send disconnect", e);
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @param sendIgnore DOCUMENT ME!
     */
    public void setSendIgnore(boolean sendIgnore) {
        this.sendIgnore = sendIgnore;
    }

    /**
     * DOCUMENT ME!
     *
     * @param seconds DOCUMENT ME!
     *
     * @throws TransportProtocolException DOCUMENT ME!
     */
    public void setKexTimeout(long seconds) throws TransportProtocolException {
        if (seconds < 60) {
            throw new TransportProtocolException(
                "Keys can only be re-exchanged every minute or more");
        }

        kexTimeout = seconds * 1000;
    }

    /**
     * DOCUMENT ME!
     *
     * @param kilobytes DOCUMENT ME!
     *
     * @throws TransportProtocolException DOCUMENT ME!
     */
    public void setKexTransferLimit(long kilobytes)
        throws TransportProtocolException {
        if (kilobytes < 10) {
            throw new TransportProtocolException(
                "Keys can only be re-exchanged after every 10k of data, or more");
        }

        kexTransferLimit = kilobytes * 1024;
    }

    /*public InetSocketAddress getRemoteAddress() {

      return (InetSocketAddress)socket.getRemoteSocketAddress();

         }*/
    public long getOutgoingByteCount() {
        return sshOut.getNumBytesTransfered();
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public long getIncomingByteCount() {
        return sshIn.getNumBytesTransfered();
    }

    /**
     * DOCUMENT ME!
     *
     * @param eventHandler DOCUMENT ME!
     */
    public void addEventHandler(TransportProtocolEventHandler eventHandler) {
        if (eventHandler != null) {
            eventHandlers.add(eventHandler);
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @throws MessageAlreadyRegisteredException DOCUMENT ME!
     */
    public abstract void registerTransportMessages()
        throws MessageAlreadyRegisteredException;

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public byte[] getSessionIdentifier() {
        return (byte[]) sessionIdentifier.clone();
    }

    /**
     * DOCUMENT ME!
     */
    public void run() {
        try {
            state.setValue(TransportProtocolState.NEGOTIATING_PROTOCOL);

            log.info("Registering transport protocol messages with inputstream");

            algorithmsOut = new TransportProtocolAlgorithmSync();

            algorithmsIn = new TransportProtocolAlgorithmSync();

            // Create the input/output streams
            sshIn = new TransportProtocolInputStream(this,
                    provider.getInputStream(), algorithmsIn);

            sshOut = new TransportProtocolOutputStream(provider.getOutputStream(),
                    this, algorithmsOut);

            // Register the transport layer messages that this class will handle
            messageStore.registerMessage(SshMsgDisconnect.SSH_MSG_DISCONNECT,
                SshMsgDisconnect.class);

            messageStore.registerMessage(SshMsgIgnore.SSH_MSG_IGNORE,
                SshMsgIgnore.class);

            messageStore.registerMessage(SshMsgUnimplemented.SSH_MSG_UNIMPLEMENTED,
                SshMsgUnimplemented.class);

            messageStore.registerMessage(SshMsgDebug.SSH_MSG_DEBUG,
                SshMsgDebug.class);

            messageStore.registerMessage(SshMsgKexInit.SSH_MSG_KEX_INIT,
                SshMsgKexInit.class);

            messageStore.registerMessage(SshMsgNewKeys.SSH_MSG_NEWKEYS,
                SshMsgNewKeys.class);

            registerTransportMessages();

            List list = SshKeyExchangeFactory.getSupportedKeyExchanges();

            Iterator it = list.iterator();

            while (it.hasNext()) {
                String keyExchange = (String) it.next();

                SshKeyExchange kex = SshKeyExchangeFactory.newInstance(keyExchange);

                kex.init(this);

                kexs.put(keyExchange, kex);
            }

            // call abstract to initialise the local ident string
            setLocalIdent();

            // negotiate the protocol version
            negotiateVersion();

            startBinaryPacketProtocol();
        } catch (Throwable e) {
            if (e instanceof IOException) {
                state.setLastError((IOException) e);
            }

            if (state.getValue() != TransportProtocolState.DISCONNECTED) {
                log.info("The Transport Protocol thread failed", e);

                stop();
            }
        } finally {
            thread = null;
        }

        log.debug("The Transport Protocol has been stopped");
    }

    /**
     * DOCUMENT ME!
     *
     * @param msg DOCUMENT ME!
     * @param sender DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     * @throws TransportProtocolException DOCUMENT ME!
     */
    public synchronized void sendMessage(SshMessage msg, Object sender)
        throws IOException {
        // Send a message, if were in key exchange then add it to
        // the list unless of course it is a transport protocol or key
        // exchange message
        log.info("Sending " + msg.getMessageName());

        int currentState = state.getValue();

        if (sender instanceof SshKeyExchange
                || sender instanceof TransportProtocolCommon
                || (currentState == TransportProtocolState.CONNECTED)) {
            sshOut.sendMessage(msg);

            if (currentState == TransportProtocolState.CONNECTED) {
                if (sendIgnore) {
                    byte[] count = new byte[1];

                    ConfigurationLoader.getRND().nextBytes(count);

                    byte[] rand = new byte[(count[0] & 0xFF) + 1];

                    ConfigurationLoader.getRND().nextBytes(rand);

                    SshMsgIgnore ignore = new SshMsgIgnore(new String(rand));

                    if (log.isDebugEnabled()) {
                        log.debug("Sending " + ignore.getMessageName());
                    }

                    sshOut.sendMessage(ignore);
                }
            }
        } else if (currentState == TransportProtocolState.PERFORMING_KEYEXCHANGE) {
            log.debug("Adding to message queue whilst in key exchange");

            synchronized (messageStack) {
                // Add this message to the end of the list
                messageStack.add(msg);
            }
        } else {
            throw new TransportProtocolException(
                "The transport protocol is disconnected");
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected abstract void onStartTransportProtocol()
        throws IOException;

    /**
     * DOCUMENT ME!
     *
     * @param provider DOCUMENT ME!
     * @param properties DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    public void startTransportProtocol(TransportProvider provider,
        SshConnectionProperties properties) throws IOException {
        // Save the connected socket for later use
        this.provider = provider;

        this.properties = properties;

        // Start the transport layer message loop
        log.info("Starting transport protocol");

        thread = new SshThread(this, "Transport protocol", true);

        thread.start();

        onStartTransportProtocol();
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public String getUnderlyingProviderDetail() {
        return provider.getProviderDetail();
    }

    /**
     * DOCUMENT ME!
     *
     * @param messageId DOCUMENT ME!
     * @param store DOCUMENT ME!
     *
     * @throws MessageNotRegisteredException DOCUMENT ME!
     */
    public void unregisterMessage(Integer messageId, SshMessageStore store)
        throws MessageNotRegisteredException {
        if (log.isDebugEnabled()) {
            log.debug("Unregistering message Id " + messageId.toString());
        }

        if (!messageNotifications.containsKey(messageId)) {
            throw new MessageNotRegisteredException(messageId);
        }

        SshMessageStore actual = (SshMessageStore) messageNotifications.get(messageId);

        if (!store.equals(actual)) {
            throw new MessageNotRegisteredException(messageId, store);
        }

        messageNotifications.remove(messageId);
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected abstract String getDecryptionAlgorithm()
        throws AlgorithmNotAgreedException;

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected abstract String getEncryptionAlgorithm()
        throws AlgorithmNotAgreedException;

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected abstract String getInputStreamCompAlgortihm()
        throws AlgorithmNotAgreedException;

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected abstract String getInputStreamMacAlgorithm()
        throws AlgorithmNotAgreedException;

    /**
     * DOCUMENT ME!
     */
    protected abstract void setLocalIdent();

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public abstract String getLocalId();

    /**
     * DOCUMENT ME!
     *
     * @param msg DOCUMENT ME!
     */
    protected abstract void setLocalKexInit(SshMsgKexInit msg);

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    protected abstract SshMsgKexInit getLocalKexInit();

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected abstract String getOutputStreamCompAlgorithm()
        throws AlgorithmNotAgreedException;

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected abstract String getOutputStreamMacAlgorithm()
        throws AlgorithmNotAgreedException;

    /**
     * DOCUMENT ME!
     *
     * @param ident DOCUMENT ME!
     */
    protected abstract void setRemoteIdent(String ident);

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public abstract String getRemoteId();

    /**
     * DOCUMENT ME!
     *
     * @param msg DOCUMENT ME!
     */
    protected abstract void setRemoteKexInit(SshMsgKexInit msg);

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    protected abstract SshMsgKexInit getRemoteKexInit();

    /**
     * DOCUMENT ME!
     *
     * @param kex DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     * @throws KeyExchangeException DOCUMENT ME!
     */
    protected abstract void performKeyExchange(SshKeyExchange kex)
        throws IOException, KeyExchangeException;

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected String getKexAlgorithm() throws AlgorithmNotAgreedException {
        return determineAlgorithm(clientKexInit.getSupportedKex(),
            serverKexInit.getSupportedKex());
    }

    /**
     * DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     * @throws KeyExchangeException DOCUMENT ME!
     */
    protected void beginKeyExchange() throws IOException, KeyExchangeException {
        log.info("Starting key exchange");

        //state.setValue(TransportProtocolState.PERFORMING_KEYEXCHANGE);
        String kexAlgorithm = "";

        // We now have both kex inits, this is where client/server
        // implemtations take over so call abstract methods
        try {
            // Determine the key exchange algorithm
            kexAlgorithm = getKexAlgorithm();

            if (log.isDebugEnabled()) {
                log.debug("Key exchange algorithm: " + kexAlgorithm);
            }

            // Get an instance of the key exchange algortihm
            SshKeyExchange kex = (SshKeyExchange) kexs.get(kexAlgorithm);

            // Do the key exchange
            performKeyExchange(kex);

            // Record the output
            exchangeHash = kex.getExchangeHash();

            if (sessionIdentifier == null) {
                sessionIdentifier = new byte[exchangeHash.length];
                System.arraycopy(exchangeHash, 0, sessionIdentifier, 0,
                    sessionIdentifier.length);
                thread.setSessionId(sessionIdentifier);
            }

            hostKey = kex.getHostKey();
            signature = kex.getSignature();
            k = kex.getSecret();

            // Send new keys
            sendNewKeys();

            kex.reset();
        } catch (AlgorithmNotAgreedException e) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "No suitable key exchange algorithm was agreed");

            throw new KeyExchangeException(
                "No suitable key exchange algorithm could be agreed.");
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected SshMsgKexInit createLocalKexInit() throws IOException {
        return new SshMsgKexInit(properties);
    }

    /**
     * DOCUMENT ME!
     */
    protected void onCorruptMac() {
        log.fatal("Corrupt Mac on Input");

        // Send a disconnect message
        sendDisconnect(SshMsgDisconnect.MAC_ERROR, "Corrupt Mac on input",
            new SshException("Corrupt Mac on Imput"));
    }

    /**
     * DOCUMENT ME!
     *
     * @param msg DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected abstract void onMessageReceived(SshMessage msg)
        throws IOException;

    /**
     * DOCUMENT ME!
     *
     * @param reason DOCUMENT ME!
     * @param description DOCUMENT ME!
     */
    protected void sendDisconnect(int reason, String description) {
        SshMsgDisconnect msg = new SshMsgDisconnect(reason, description, "");

        try {
            sendMessage(msg, this);
            stop();
        } catch (Exception e) {
            log.warn("Failed to send disconnect", e);
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @param reason DOCUMENT ME!
     * @param description DOCUMENT ME!
     * @param error DOCUMENT ME!
     */
    protected void sendDisconnect(int reason, String description,
        IOException error) {
        state.setLastError(error);

        sendDisconnect(reason, description);
    }

    /**
     * DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected void sendKeyExchangeInit() throws IOException {
        setLocalKexInit(createLocalKexInit());

        sendMessage(getLocalKexInit(), this);

        state.setValue(TransportProtocolState.PERFORMING_KEYEXCHANGE);
    }

    /**
     * DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected void sendNewKeys() throws IOException {
        // Send new keys
        SshMsgNewKeys msg = new SshMsgNewKeys();

        sendMessage(msg, this);

        // Lock the outgoing algorithms so nothing else is sent untill
        // weve updated them with the new keys
        algorithmsOut.lock();
        
        // Do we need to hold the algorithmsOut lock during
        // the input message handling below? If not, then the 
        // lock could be taken just before completeKeyExchange 
        // or even moved into the completeKeyExchange method.
        // We would then not need the try-finally below (which
        // is needed for exceptions from eg the readMessage call).
        boolean hasReleasedLock = false;
        try {
            int[] filter = new int[1];
            filter[0] = SshMsgNewKeys.SSH_MSG_NEWKEYS;
            msg = (SshMsgNewKeys) readMessage(filter);
            
            if (log.isDebugEnabled()) {
                log.debug("Received " + msg.getMessageName());
            }
            
            // Release done in completeKeyExchange
            hasReleasedLock = true;
            completeKeyExchange();
        }
        finally {
            if( ! hasReleasedLock ) {
                algorithmsOut.release();
            }
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @param encryptCSKey DOCUMENT ME!
     * @param encryptCSIV DOCUMENT ME!
     * @param encryptSCKey DOCUMENT ME!
     * @param encryptSCIV DOCUMENT ME!
     * @param macCSKey DOCUMENT ME!
     * @param macSCKey DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     * @throws AlgorithmOperationException DOCUMENT ME!
     * @throws AlgorithmNotSupportedException DOCUMENT ME!
     * @throws AlgorithmInitializationException DOCUMENT ME!
     */
    protected abstract void setupNewKeys(byte[] encryptCSKey,
        byte[] encryptCSIV, byte[] encryptSCKey, byte[] encryptSCIV,
        byte[] macCSKey, byte[] macSCKey)
        throws AlgorithmNotAgreedException, AlgorithmOperationException, 
            AlgorithmNotSupportedException, AlgorithmInitializationException;

    /**
     * DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     * @throws TransportProtocolException DOCUMENT ME!
     */
    protected void completeKeyExchange() throws IOException {
        log.info("Completing key exchange");

	boolean hasReleasedLock = false;
        try {
            // Reset the state variables
            //completeOnNewKeys = new Boolean(false);
            log.debug("Making keys from key exchange output");

            // Make the keys
            byte[] encryptionKey = makeSshKey('C');

            byte[] encryptionIV = makeSshKey('A');

            byte[] decryptionKey = makeSshKey('D');

            byte[] decryptionIV = makeSshKey('B');

            byte[] sendMac = makeSshKey('E');

            byte[] receiveMac = makeSshKey('F');

            log.debug("Creating algorithm objects");

            setupNewKeys(encryptionKey, encryptionIV, decryptionKey,
                decryptionIV, sendMac, receiveMac);

            // Reset the key exchange
            clientKexInit = null;

            serverKexInit = null;

            //algorithmsIn.release();
            algorithmsOut.release();
            hasReleasedLock = true;

            /*

             *  Update our state, we can send all packets

             *

             */
            state.setValue(TransportProtocolState.CONNECTED);

            // Send any outstanding messages
            synchronized (messageStack) {
                Iterator it = messageStack.iterator();

                log.debug("Sending queued messages");

                while (it.hasNext()) {
                    SshMessage msg = (SshMessage) it.next();

                    sendMessage(msg, this);
                }

                messageStack.clear();
            }
        } catch (AlgorithmNotAgreedException anae) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "Algorithm not agreed");

            throw new TransportProtocolException(
                "The connection was disconnected because an algorithm could not be agreed");
        } catch (AlgorithmNotSupportedException anse) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "Application error");

            throw new TransportProtocolException(
                "The connection was disconnected because an algorithm class could not be loaded");
        } catch (AlgorithmOperationException aoe) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "Algorithm operation error");

            throw new TransportProtocolException(
                "The connection was disconnected because"
                + " of an algorithm operation error");
        } catch (AlgorithmInitializationException aie) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "Algorithm initialization error");

            throw new TransportProtocolException(
                "The connection was disconnected because"
                + " of an algorithm initialization error");
        }
        finally {
            if( ! hasReleasedLock ) {
                algorithmsOut.release();
            }
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    protected List getEventHandlers() {
        return eventHandlers;
    }

    /**
     * DOCUMENT ME!
     *
     * @param clientAlgorithms DOCUMENT ME!
     * @param serverAlgorithms DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws AlgorithmNotAgreedException DOCUMENT ME!
     */
    protected String determineAlgorithm(List clientAlgorithms,
        List serverAlgorithms) throws AlgorithmNotAgreedException {
        if (log.isDebugEnabled()) {
            log.debug("Determine Algorithm");

            log.debug("Client Algorithms: " + clientAlgorithms.toString());

            log.debug("Server Algorithms: " + serverAlgorithms.toString());
        }

        String algorithmClient;

        String algorithmServer;

        Iterator itClient = clientAlgorithms.iterator();

        while (itClient.hasNext()) {
            algorithmClient = (String) itClient.next();

            Iterator itServer = serverAlgorithms.iterator();

            while (itServer.hasNext()) {
                algorithmServer = (String) itServer.next();

                if (algorithmClient.equals(algorithmServer)) {
                    log.debug("Returning " + algorithmClient);

                    return algorithmClient;
                }
            }
        }

        throw new AlgorithmNotAgreedException("Could not agree algorithm");
    }

    /**
     * DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected void startBinaryPacketProtocol() throws IOException {
        // Send our Kex Init
        sendKeyExchangeInit();

        SshMessage msg;

        // Perform a transport protocol message loop
        while (state.getValue() != TransportProtocolState.DISCONNECTED) {
            // Process incoming messages returning any transport protocol
            // messages to be handled here
            msg = processMessages();

            log.info("Received " + msg.getMessageName());

            switch (msg.getMessageId()) {
            case SshMsgKexInit.SSH_MSG_KEX_INIT: {
                onMsgKexInit((SshMsgKexInit) msg);

                break;
            }

            case SshMsgDisconnect.SSH_MSG_DISCONNECT: {
                onMsgDisconnect((SshMsgDisconnect) msg);

                break;
            }

            case SshMsgIgnore.SSH_MSG_IGNORE: {
                onMsgIgnore((SshMsgIgnore) msg);

                break;
            }

            case SshMsgUnimplemented.SSH_MSG_UNIMPLEMENTED: {
                onMsgUnimplemented((SshMsgUnimplemented) msg);

                break;
            }

            case SshMsgDebug.SSH_MSG_DEBUG: {
                onMsgDebug((SshMsgDebug) msg);

                break;
            }

            default:
                onMessageReceived(msg);
            }
        }
    }

    /**
     * DOCUMENT ME!
     */
    protected final void stop() {
        state.setValue(TransportProtocolState.DISCONNECTED);

        onDisconnect();

        Iterator it = eventHandlers.iterator();

        TransportProtocolEventHandler eventHandler;

        while (it.hasNext()) {
            eventHandler = (TransportProtocolEventHandler) it.next();

            eventHandler.onDisconnect(this);
        }

        // Close the input/output streams
        //sshIn.close();
        if (messageStore != null) {
            messageStore.close();
    
            // 05/01/2003 moiz change begin:
            // all close all the registerd messageStores
            SshMessageStore ms;
    
            for (it = messageStores.iterator(); (it != null) && it.hasNext();) {
                ms = (SshMessageStore) it.next();
    
                try {
                    ms.close();
                } catch (Exception e) {
                }
            }
    
            // 05/01/2003 moizd change end:
            messageStore = null;
        }
        try {
            provider.close();
        } catch (IOException ioe) {
        }
    }

    private byte[] makeSshKey(char chr) throws IOException {
        try {
            // Create the first 20 bytes of key data
            ByteArrayWriter keydata = new ByteArrayWriter();

            byte[] data = new byte[20];

            Hash hash = new Hash("SHA");

            // Put the dh k value
            hash.putBigInteger(k);

            // Put in the exchange hash
            hash.putBytes(exchangeHash);

            // Put in the character
            hash.putByte((byte) chr);

            // Put the exchange hash in again
            hash.putBytes(sessionIdentifier);

            // Create the fist 20 bytes
            data = hash.doFinal();

            keydata.write(data);

            // Now do the next 20
            hash.reset();

            // Put the dh k value in again
            hash.putBigInteger(k);

            // And the exchange hash
            hash.putBytes(exchangeHash);

            // Finally the first 20 bytes of data we created
            hash.putBytes(data);

            data = hash.doFinal();

            // Put it all together
            keydata.write(data);

            // Return it
            return keydata.toByteArray();
        } catch (NoSuchAlgorithmException nsae) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "Application error");

            throw new TransportProtocolException("SHA algorithm not supported");
        } catch (IOException ioe) {
            sendDisconnect(SshMsgDisconnect.KEY_EXCHANGE_FAILED,
                "Application error");

            throw new TransportProtocolException("Error writing key data");
        }
    }

    private void negotiateVersion() throws IOException {
        byte[] buf;

        int len;

        String remoteVer = "";

        log.info("Negotiating protocol version");

        log.debug("Local identification: " + getLocalId());

        // Get the local ident string by calling the abstract method, this
        // way the implementations set the correct variables for computing the
        // exchange hash
        String data = getLocalId() + "\r\n";

        // Send our version string
        provider.getOutputStream().write(data.getBytes());

        // Now wait for a reply and evaluate the ident string
        //buf = new byte[255];
        StringBuffer buffer = new StringBuffer();

        char ch;

        int MAX_BUFFER_LENGTH = 255;

        // Look for a string starting with "SSH-"
        while (!remoteVer.startsWith("SSH-")
                && (buffer.length() < MAX_BUFFER_LENGTH)) {
            // Get the next string
            while (((ch = (char) provider.getInputStream().read()) != '\n')
                    && (buffer.length() < MAX_BUFFER_LENGTH)) {
            	if (ch == (char) -1) {
            		throw new ThrottlingException();
            	}
                buffer.append(ch);
            }

            // Set trimming off any EOL characters
            remoteVer = buffer.toString();

            // Guess the remote sides EOL by looking at the end of the ident string
            if (remoteVer.endsWith("\r")) {
                remoteEOL = EOL_CRLF;
            } else {
                remoteEOL = EOL_LF;
            }

            log.debug("EOL is guessed at "
                + ((remoteEOL == EOL_CRLF) ? "CR+LF" : "LF"));

            // Remove any \r
            remoteVer = remoteVer.trim();
        }

        // Get the index of the seperators
        int l = remoteVer.indexOf("-");

        int r = remoteVer.indexOf("-", l + 1);

        // Call abstract method so the implementations can set the
        // correct member variable
        setRemoteIdent(remoteVer.trim());

        if (log.isDebugEnabled()) {
            log.debug("Remote identification: " + getRemoteId());
        }

        // Get the version
        String remoteVersion = remoteVer.substring(l + 1, r);

        // Evaluate the version, we only support 2.0
        if (!(remoteVersion.equals("2.0") || (remoteVersion.equals("1.99")))) {
            log.fatal(
                "The remote computer does not support protocol version 2.0");

            throw new TransportProtocolException(
                "The protocol version of the remote computer is not supported!");
        }

        log.info("Protocol negotiation complete");
    }

    private void onMsgDebug(SshMsgDebug msg) {
        log.debug(msg.getMessage());
    }

    private void onMsgDisconnect(SshMsgDisconnect msg)
        throws IOException {
        log.info("The remote computer disconnected: " + msg.getDescription());

        state.setDisconnectReason(msg.getDescription());

        stop();
    }

    private void onMsgIgnore(SshMsgIgnore msg) {
        if (log.isDebugEnabled()) {
            log.debug("SSH_MSG_IGNORE with "
                + String.valueOf(msg.getData().length()) + " bytes of data");
        }
    }

    private void onMsgKexInit(SshMsgKexInit msg) throws IOException {
        log.debug("Received remote key exchange init message");

        log.debug(msg.toString());

        synchronized (kexLock) {
            setRemoteKexInit(msg);

            // As either party can initiate a key exchange then we
            // must check to see if we have sent our own
            if (state.getValue() != TransportProtocolState.PERFORMING_KEYEXCHANGE) {
                //if (getLocalKexInit() == null) {
                sendKeyExchangeInit();
            }

            //}
            beginKeyExchange();
        }
    }

    private void onMsgNewKeys(SshMsgNewKeys msg) throws IOException {
        // Determine whether we have completed our own
        log.debug("Received New Keys");

        //algorithmsIn.lock();

        synchronized (completeOnNewKeys) {
            if (completeOnNewKeys.booleanValue()) {
        	// We need to take this lock since
                // it is released in completeKeyExchange.
                algorithmsOut.lock();
                completeKeyExchange();
            } else {
                completeOnNewKeys = new Boolean(true);
            }
        }
    }

    private void onMsgUnimplemented(SshMsgUnimplemented msg) {
        if (log.isDebugEnabled()) {
            log.debug("The message with sequence no " + msg.getSequenceNo()
                + " was reported as unimplemented by the remote end.");
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @param filter DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    public SshMessage readMessage(int[] filter) throws IOException {
        byte[] msgdata = null;

        SshMessage msg;

        while (state.getValue() != TransportProtocolState.DISCONNECTED) {
            boolean hasmsg = false;

            while (!hasmsg) {
                msgdata = sshIn.readMessage();

                hasmsg = true;
            }

            Integer messageId = SshMessage.getMessageId(msgdata);

            // First check the filter
            for (int i = 0; i < filter.length; i++) {
                if (filter[i] == messageId.intValue()) {
                    if (messageStore.isRegisteredMessage(messageId)) {
                        return messageStore.createMessage(msgdata);
                    } else {
                        SshMessageStore ms = getMessageStore(messageId);

                        msg = ms.createMessage(msgdata);

                        if (log.isDebugEnabled()) {
                            log.debug("Processing " + msg.getMessageName());
                        }

                        return msg;
                    }
                }
            }

            if (messageStore.isRegisteredMessage(messageId)) {
                msg = messageStore.createMessage(msgdata);

                switch (messageId.intValue()) {
                case SshMsgDisconnect.SSH_MSG_DISCONNECT: {
                    onMsgDisconnect((SshMsgDisconnect) msg);

                    break;
                }

                case SshMsgIgnore.SSH_MSG_IGNORE: {
                    onMsgIgnore((SshMsgIgnore) msg);

                    break;
                }

                case SshMsgUnimplemented.SSH_MSG_UNIMPLEMENTED: {
                    onMsgUnimplemented((SshMsgUnimplemented) msg);

                    break;
                }

                case SshMsgDebug.SSH_MSG_DEBUG: {
                    onMsgDebug((SshMsgDebug) msg);

                    break;
                }

                default: // Exception not allowed
                    throw new IOException(
                        "Unexpected transport protocol message");
                }
            } else {
                throw new IOException("Unexpected message received");
            }
        }

        throw new IOException("The transport protocol disconnected");
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws IOException DOCUMENT ME!
     */
    protected SshMessage processMessages() throws IOException {
        byte[] msgdata = null;

        SshMessage msg;

        SshMessageStore ms;

        while (state.getValue() != TransportProtocolState.DISCONNECTED) {
            long currentTime = System.currentTimeMillis();

            if (((currentTime - startTime) > kexTimeout)
                    || ((sshIn.getNumBytesTransfered()
                    + sshOut.getNumBytesTransfered()) > kexTransferLimit)) {
                startTime = currentTime;

                sendKeyExchangeInit();
            }

            boolean hasmsg = false;

            while (!hasmsg) {
                try {
                    msgdata = sshIn.readMessage();

                    hasmsg = true;
                } catch (InterruptedIOException ex /*SocketTimeoutException ex*/) {
                    log.info("Possible timeout on transport inputstream");

                    Iterator it = eventHandlers.iterator();

                    TransportProtocolEventHandler eventHandler;

                    while (it.hasNext()) {
                        eventHandler = (TransportProtocolEventHandler) it.next();

                        eventHandler.onSocketTimeout(this /*,

                        provider.isConnected()*/);
                    }
                }
            }

            Integer messageId = SshMessage.getMessageId(msgdata);

            if (!messageStore.isRegisteredMessage(messageId)) {
                try {
                    ms = getMessageStore(messageId);

                    msg = ms.createMessage(msgdata);

                    log.info("Received " + msg.getMessageName());

                    ms.addMessage(msg);
                } catch (MessageNotRegisteredException mnre) {
                    log.info("Unimplemented message received "
                        + String.valueOf(messageId.intValue()));

                    msg = new SshMsgUnimplemented(sshIn.getSequenceNo());

                    sendMessage(msg, this);
                }
            } else {
                return messageStore.createMessage(msgdata);
            }
        }

        throw new IOException("The transport protocol has disconnected");
    }

    /**
     * DOCUMENT ME!
     *
     * @param store DOCUMENT ME!
     *
     * @throws MessageAlreadyRegisteredException DOCUMENT ME!
     */
    public void addMessageStore(SshMessageStore store)
        throws MessageAlreadyRegisteredException {
        messageStores.add(store);
    }

    private SshMessageStore getMessageStore(Integer messageId)
        throws MessageNotRegisteredException {
        SshMessageStore ms;

        for (Iterator it = messageStores.iterator();
                (it != null) && it.hasNext();) {
            ms = (SshMessageStore) it.next();

            if (ms.isRegisteredMessage(messageId)) {
                return ms;
            }
        }

        throw new MessageNotRegisteredException(messageId);
    }

    /**
     * DOCUMENT ME!
     *
     * @param ms DOCUMENT ME!
     */
    public void removeMessageStore(SshMessageStore ms) {
        messageStores.remove(ms);
    }
}
