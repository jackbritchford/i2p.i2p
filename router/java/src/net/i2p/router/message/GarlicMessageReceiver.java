package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Decrypt a garlic message and pass off any valid cloves to the configured
 * receiver to dispatch as they choose.
 *
 */
public class GarlicMessageReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final CloveReceiver _receiver;
    private final Hash _clientDestination;
   
    public interface CloveReceiver {
        public void handleClove(DeliveryInstructions instructions, I2NPMessage data);
    }
    
    /**
     *  @param receiver non-null
     */
    public GarlicMessageReceiver(RouterContext context, CloveReceiver receiver) {
        this(context, receiver, null);
    }

    /**
     *  @param receiver non-null
     */
    public GarlicMessageReceiver(RouterContext context, CloveReceiver receiver, Hash clientDestination) {
        _context = context;
        _log = context.logManager().getLog(GarlicMessageReceiver.class);
        _clientDestination = clientDestination;
        _receiver = receiver;
        //_log.error("New GMR dest = " + clientDestination);
        // all createRateStat in OCMOSJ.init()
    }
    
    public void receive(GarlicMessage message) {
        PrivateKey decryptionKey;
        SessionKeyManager skm;
        if (_clientDestination != null) {
            LeaseSetKeys keys = _context.keyManager().getKeys(_clientDestination);
            skm = _context.clientManager().getClientSessionKeyManager(_clientDestination);
            if (keys != null && skm != null) {
                // TODO need to pass both keys if available for muxed decrypt
                decryptionKey = keys.getDecryptionKey();
                if (decryptionKey == null) {
                    decryptionKey = keys.getDecryptionKey(EncType.ECIES_X25519);
                    if (decryptionKey == null) {
                        if (_log.shouldWarn())
                            _log.warn("No key to decrypt for " + _clientDestination.toBase32());
                        return;
                    }
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not trying to decrypt a garlic routed message to a disconnected client");
                return;
            }
        } else {
            decryptionKey = _context.keyManager().getPrivateKey();
            skm = _context.sessionKeyManager();
        }
        
        // TODO need to pass both keys if available for muxed decrypt
        CloveSet set = _context.garlicMessageParser().getGarlicCloves(message, decryptionKey, skm);
        if (set != null) {
            for (int i = 0; i < set.getCloveCount(); i++) {
                GarlicClove clove = set.getClove(i);
                handleClove(clove);
            }
        } else {
            if (_log.shouldLog(Log.WARN)) {
                String d = (_clientDestination != null) ? _clientDestination.toBase32() : "the router";
                _log.warn("CloveMessageParser failed to decrypt the " + message.getData().length +
                          " byte message [" + message.getUniqueId() 
                           + "] for " + d + " with key " + decryptionKey.getType(),
                          new Exception("Decrypt garlic failed"));
            }
            _context.statManager().addRateData("crypto.garlic.decryptFail", 1);
            _context.messageHistory().messageProcessingError(message.getUniqueId(), 
                                                             message.getClass().getName(), 
                                                             "Garlic could not be decrypted");
        }
    }

    /**
     * Validate and pass off any valid cloves to the receiver
     *
     */
    private void handleClove(GarlicClove clove) {
        if (!isValid(clove)) {
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("Invalid clove " + clove);
            return;
        } 
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("valid clove " + clove);
        _receiver.handleClove(clove.getInstructions(), clove.getData());
    }
    
    private boolean isValid(GarlicClove clove) {
        String invalidReason = _context.messageValidator().validateMessage(clove.getCloveId(), 
                                                                          clove.getExpiration().getTime());
        boolean rv = invalidReason == null;
        if (!rv) {
            String howLongAgo = DataHelper.formatDuration(_context.clock().now()-clove.getExpiration().getTime());
            if (_log.shouldInfo())
                _log.info("Clove is NOT valid: id=" + clove.getCloveId() 
                           + " expiration " + howLongAgo + " ago", new Exception("Invalid within..."));
            else if (_log.shouldWarn())
                _log.warn("Clove is NOT valid: id=" + clove.getCloveId() 
                           + " expiration " + howLongAgo + " ago: " + invalidReason + ": " + clove);
            _context.messageHistory().messageProcessingError(clove.getCloveId(), 
                                                             clove.getData().getClass().getSimpleName(), 
                                                             "Clove is not valid (expiration " + howLongAgo + " ago)");
        }
        return rv;
    }
}
