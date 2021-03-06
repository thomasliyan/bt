package bt.net;

import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.protocol.DecodingContext;
import bt.protocol.Handshake;
import bt.protocol.Message;
import bt.protocol.Protocols;
import bt.protocol.crypto.EncryptionPolicy;
import bt.protocol.crypto.MSECipher;
import bt.protocol.handler.MessageHandler;
import bt.torrent.TorrentDescriptor;
import bt.torrent.TorrentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Implements Message Stream Encryption protocol negotiation.
 *
 * This class is not a part of the public API and is subject to change.
 */
class MSEHandshakeProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MSEHandshakeProcessor.class);

    private static final Duration receiveTimeout = Duration.ofSeconds(10);
    private static final Duration waitBetweenReads = Duration.ofSeconds(1);
    private static final int paddingMaxLength = 512;
    private static final byte[] VC_RAW_BYTES = new byte[8];

    private final MSEKeyPairGenerator keyGenerator;
    private final TorrentRegistry torrentRegistry;
    private final MessageHandler<Message> messageHandler;
    private final EncryptionPolicy localEncryptionPolicy;
    private final int bufferSize;

    MSEHandshakeProcessor(TorrentRegistry torrentRegistry,
                                 MessageHandler<Message> messageHandler,
                                 EncryptionPolicy localEncryptionPolicy,
                                 int bufferSize) {
        this.keyGenerator = new MSEKeyPairGenerator();
        this.torrentRegistry = torrentRegistry;
        this.messageHandler = messageHandler;
        this.localEncryptionPolicy = localEncryptionPolicy;
        this.bufferSize = bufferSize;
    }

    MessageReaderWriter negotiateOutgoing(Peer peer, ByteChannel channel, TorrentId torrentId) throws IOException {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Negotiating encryption for outgoing connection: {}", peer);
        }
        /**
         * Blocking steps:
         *
         * 1. A->B: Diffie Hellman Ya, PadA
         * 2. B->A: Diffie Hellman Yb, PadB
         * 3. A->B:
         *  - HASH('req1', S),
         *  - HASH('req2', SKEY) xor HASH('req3', S),
         *  - ENCRYPT(VC, crypto_provide, len(PadC), PadC, len(IA)),
         *  - ENCRYPT(IA)
         * 4. B->A:
         * - ENCRYPT(VC, crypto_select, len(padD), padD),
         * - ENCRYPT2(Payload Stream)
         * 5. A->B: ENCRYPT2(Payload Stream)
         */

        // check if the encryption negotiation can be skipped or preemptively aborted
        EncryptionPolicy peerEncryptionPolicy = peer.getOptions().getEncryptionPolicy();
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Peer {} has encryption policy: {}", peer, peerEncryptionPolicy);
        }
        assertPolicyIsCompatible(peerEncryptionPolicy);

        ByteBuffer in = ByteBuffer.allocateDirect(bufferSize);
        ByteBuffer out = ByteBuffer.allocateDirect(bufferSize);

        if (peerEncryptionPolicy == EncryptionPolicy.REQUIRE_PLAINTEXT ||
                (peerEncryptionPolicy == EncryptionPolicy.PREFER_PLAINTEXT &&
                        (localEncryptionPolicy == EncryptionPolicy.PREFER_PLAINTEXT
                                || localEncryptionPolicy == EncryptionPolicy.REQUIRE_PLAINTEXT))) {
            // if peer requires plaintext and we support it, then do not negotiate encryption and use plaintext right away
            return createReaderWriter(peer, channel, in, out);
        }

        ByteChannelReader reader = reader(channel);

        // 1. A->B: Diffie Hellman Ya, PadA
        // send our public key
        KeyPair keys = keyGenerator.generateKeyPair();
        out.put(keys.getPublic().getEncoded());
        out.put(getPadding(paddingMaxLength));
        out.flip();
        channel.write(out);
        out.clear();

        // 2. B->A: Diffie Hellman Yb, PadB
        // receive peer's public key
        int phase1Min = keyGenerator.getPublicKeySize();
        int phase1Limit = phase1Min + paddingMaxLength;
        int phase1Read = reader.readBetween(phase1Min, phase1Limit).read(in);
        in.flip();
        BigInteger peerPublicKey = BigIntegers.decodeUnsigned(in, phase1Min);
        in.clear(); // discard the padding, if present

        // calculate shared secret S
        BigInteger S = keyGenerator.calculateSharedSecret(peerPublicKey, keys.getPrivate());

        // 3. A->B:
        MessageDigest digest = getDigest("SHA-1");
        // - HASH('req1', S)
        digest.update("req1".getBytes("ASCII"));
        digest.update(BigIntegers.encodeUnsigned(S, keyGenerator.getPublicKeySize()));
        out.put(digest.digest());
        // - HASH('req2', SKEY) xor HASH('req3', S)
        digest.update("req2".getBytes("ASCII"));
        digest.update(torrentId.getBytes());
        byte[] b1 = digest.digest();
        digest.update("req3".getBytes("ASCII"));
        digest.update(BigIntegers.encodeUnsigned(S, keyGenerator.getPublicKeySize()));
        byte[] b2 = digest.digest();
        out.put(xor(b1, b2));
        // write
        out.flip();
        channel.write(out);
        out.clear();

        byte[] Sbytes = BigIntegers.encodeUnsigned(S, MSEKeyPairGenerator.PUBLIC_KEY_BYTES);
        MSECipher cipher = MSECipher.forInitiator(Sbytes, torrentId);
        ByteChannel encryptedChannel = new EncryptedChannel(channel, cipher.getDecryptionCipher(), cipher.getEncryptionCipher());
        // - ENCRYPT(VC, crypto_provide, len(PadC), PadC, len(IA))
        out.put(VC_RAW_BYTES);
        out.put(getCryptoProvideBitfield(localEncryptionPolicy));
        byte[] padding = getZeroPadding(512);
        out.put(Protocols.getShortBytes(padding.length));
        out.put(padding);
        // - ENCRYPT(IA)
        // do not write IA (initial payload data) for now, wait for encryption negotiation
        out.putShort((short) 0); // IA length = 0
        out.flip();
        encryptedChannel.write(out);
        out.clear();

        // 4. B->A:
        // - ENCRYPT(VC, crypto_select, len(padD), padD)
        byte[] encryptedVC;
        {
            MSECipher throwawayCipher = MSECipher.forInitiator(Sbytes, torrentId);
            try {
                encryptedVC = throwawayCipher.getDecryptionCipher().doFinal(VC_RAW_BYTES);
            } catch (Exception e) {
                throw new RuntimeException("Failed to encrypt VC", e);
            }
        }
        // synchronize on the incoming stream of data
        int phase2Min = encryptedVC.length + 4/*crypto_select*/ + 2/*padding_len*/;
        // account for (phase1Limit - phase1Read) in case the padding from phase1 arrives later than expected
        int phase2Limit = (phase1Limit - phase1Read) + phase2Min + paddingMaxLength;

        int initpos = in.position();
        // use plaintext reader because encryption stream is not synced yet and will potentially produce garbage
        int phase2Read = reader.readBetween(phase2Min, phase2Limit).sync(in, encryptedVC);
        int matchpos = in.position();

        // the rest of the data is known to be encrypted
        ByteChannelReader encryptedReader = reader(encryptedChannel);
        // but we need to align the incoming (decrypting) cipher
        // for the number of encrypted bytes that have already arrived
        // and decrypt these bytes in the incoming data buffer for later processing
        in.limit(initpos + phase2Read);
        {
            cipher.getDecryptionCipher().update(new byte[VC_RAW_BYTES.length]);
            byte[] encryptedData = new byte[in.remaining()];
            in.get(encryptedData);
            in.position(matchpos);
            byte[] decryptedData = cipher.getDecryptionCipher().update(encryptedData);
            in.put(decryptedData);
            in.position(matchpos);
        }

        // we may still need to receive some handshake data (e.g. padding), that is arriving later than expected
        if (in.remaining() < (phase2Min - encryptedVC.length)) {
            int lim = in.limit();
            in.limit(in.capacity());
            int read = encryptedReader.readAtLeast(phase2Min - encryptedVC.length)
                    .readNoMoreThan((phase2Min - encryptedVC.length) + paddingMaxLength)
                    .read(in);
            in.position(matchpos);
            in.limit(lim + read);
        }

        byte[] crypto_select = new byte[4];
        in.get(crypto_select);
        EncryptionPolicy negotiatedEncryptionPolicy = selectPolicy(crypto_select, localEncryptionPolicy);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Negotiated encryption policy: {}, peer: {}", negotiatedEncryptionPolicy, peer);
        }

        int theirPadding = in.getShort() & 0xFFFF;
        int missing = (theirPadding - in.remaining());
        if (missing > 0) {
            int pos = in.position();
            in.limit(in.capacity());
            encryptedReader.readAtLeast(missing).read(in);
            in.flip();
            in.position(pos);
        }

        // account for the upper layer protocol data that has already arrived
        in.position(in.position() + theirPadding);
        in.compact();
        out.clear();

        // - ENCRYPT2(Payload Stream)
        switch (negotiatedEncryptionPolicy) {
            case REQUIRE_PLAINTEXT:
            case PREFER_PLAINTEXT: {
                return createReaderWriter(peer, channel, in, out);
            }
            case PREFER_ENCRYPTED:
            case REQUIRE_ENCRYPTED: {
                return createReaderWriter(peer, encryptedChannel, in, out);
            }
            default: {
                throw new IllegalStateException("Unknown encryption policy: " + negotiatedEncryptionPolicy.name());
            }
        }
    }

    MessageReaderWriter negotiateIncoming(Peer peer, ByteChannel channel) throws IOException {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Negotiating encryption for incoming connection: {}", peer);
        }
        /**
         * Blocking steps:
         *
         * 1. A->B: Diffie Hellman Ya, PadA
         * 2. B->A: Diffie Hellman Yb, PadB
         * 3. A->B:
         *  - HASH('req1', S),
         *  - HASH('req2', SKEY) xor HASH('req3', S),
         *  - ENCRYPT(VC, crypto_provide, len(PadC), PadC, len(IA)),
         *  - ENCRYPT(IA)
         * 4. B->A:
         *  - ENCRYPT(VC, crypto_select, len(padD), padD),
         *  - ENCRYPT2(Payload Stream)
         * 5. A->B: ENCRYPT2(Payload Stream)
         */

        ByteBuffer in = ByteBuffer.allocateDirect(bufferSize);
        ByteBuffer out = ByteBuffer.allocateDirect(bufferSize);
        ByteChannelReader reader = reader(channel);

        // 1. A->B: Diffie Hellman Ya, PadA
        // receive initiator's public key
        // specify lower threshold on the amount of bytes to receive,
        // as we will try to decode plaintext message of an unknown length first
        int phase0Min = 20;
        int phase0Read = reader.readAtLeast(phase0Min).read(in);
        in.flip();
        // try to determine the protocol from the first received bytes
        DecodingContext context = new DecodingContext(peer);
        int consumed = 0;
        try {
             consumed = messageHandler.decode(context, in);
        } catch (Exception e) {
            // ignore
        }
        // TODO: can this be done without knowing the protocol specifics? (KeepAlive can be especially misleading: 0x00 0x00 0x00 0x00)
        if (consumed > 0 && context.getMessage() instanceof Handshake) {
            // decoding was successful, can use plaintext (if supported)
            assertPolicyIsCompatible(EncryptionPolicy.REQUIRE_PLAINTEXT);
            return createReaderWriter(peer, channel, in, out);
        }

        int phase1Min = keyGenerator.getPublicKeySize();
        int phase1Limit = phase1Min + paddingMaxLength;
        in.limit(in.capacity());
        in.position(phase0Read);
        // verify that there is a sufficient amount of bytes to decode peer's public key
        int phase1Read;
        if (phase0Read < phase1Min) {
            phase1Read = reader.readAtLeast(phase1Min - phase0Read).readNoMoreThan(phase1Limit - phase0Read).read(in);
        } else {
            phase1Read = 0;
        }

        in.flip();
        BigInteger peerPublicKey = BigIntegers.decodeUnsigned(in, keyGenerator.getPublicKeySize());
        in.clear(); // discard padding

        // 2. B->A: Diffie Hellman Yb, PadB
        // send our public key
        KeyPair keys = keyGenerator.generateKeyPair();
        out.put(keys.getPublic().getEncoded());
        out.put(getPadding(paddingMaxLength));
        out.flip();
        channel.write(out);
        out.clear();

        // calculate shared secret S
        BigInteger S = keyGenerator.calculateSharedSecret(peerPublicKey, keys.getPrivate());

        // 3. A->B:
        int phase2Min = 20 + 20 + 8 + 4 + 2 + 0 + 2;
        int phase2Limit = 20 + 20 + 8 + 4 + 2 + 512 + 2;
        MessageDigest digest = getDigest("SHA-1");
        // padding from phase 1 may be arriving later than expected, so we need to synchronize
        // on the incoming stream of data, looking for a correct S hash
        byte[] bytes = new byte[20];
        // - HASH('req1', S)
        digest.update("req1".getBytes("ASCII"));
        digest.update(BigIntegers.encodeUnsigned(S, keyGenerator.getPublicKeySize()));
        byte[] req1hash = digest.digest();
        // syncing will also ensure that the peer knows S (otherwise synchronization will fail due to not finding the pattern)
        int phase2Read = reader.readAtLeast(phase2Min)
                .readNoMoreThan(phase2Limit + (phase1Limit - (phase0Read + phase1Read))) // account for padding arriving later
                .sync(in, req1hash);
        in.limit(phase1Read + phase2Read);

        // - HASH('req2', SKEY) xor HASH('req3', S)
        in.get(bytes); // read SKEY/S hash
        Torrent requestedTorrent = null;
        digest.update("req3".getBytes("ASCII"));
        digest.update(BigIntegers.encodeUnsigned(S, keyGenerator.getPublicKeySize()));
        byte[] b2 = digest.digest();
        for (Torrent torrent : torrentRegistry.getTorrents()) {
            digest.update("req2".getBytes("ASCII"));
            digest.update(torrent.getTorrentId().getBytes());
            byte[] b1 = digest.digest();
            if (Arrays.equals(xor(b1, b2), bytes)) {
                requestedTorrent = torrent;
                break;
            }
        }
        // check that torrent is supported and active
        boolean err = false;
        if (requestedTorrent == null) {
            err = true;
        } else {
            Optional<TorrentDescriptor> descriptor = torrentRegistry.getDescriptor(requestedTorrent);
            if (!descriptor.isPresent() || !descriptor.get().isActive()) {
                err = true;
            }
        }
        if (err) {
            throw new IllegalStateException("Unsupported/inactive torrent requested");
        }

        byte[] Sbytes = BigIntegers.encodeUnsigned(S, MSEKeyPairGenerator.PUBLIC_KEY_BYTES);
        MSECipher cipher = MSECipher.forReceiver(Sbytes, requestedTorrent.getTorrentId());
        ByteChannel encryptedChannel = new EncryptedChannel(channel, cipher.getDecryptionCipher(), cipher.getEncryptionCipher());
        ByteChannelReader encryptedReader = reader(encryptedChannel);

        // - ENCRYPT(VC, crypto_provide, len(PadC), PadC, len(IA))
        // derypt encrypted leftovers from step #3
        int pos = in.position();
        byte[] leftovers = new byte[in.remaining()];
        in.get(leftovers);
        in.position(pos);
        try {
            in.put(cipher.getDecryptionCipher().update(leftovers));
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt leftover bytes: " + leftovers.length);
        }
        in.position(pos);

        byte[] theirVC = new byte[8];
        in.get(theirVC);
        if (!Arrays.equals(VC_RAW_BYTES, theirVC)) {
            throw new IllegalStateException("Invalid VC: "+ Arrays.toString(theirVC));
        }

        byte[] crypto_provide = new byte[4];
        in.get(crypto_provide);
        EncryptionPolicy negotiatedEncryptionPolicy = selectPolicy(crypto_provide, localEncryptionPolicy);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Negotiated encryption policy: {}, peer: {}", negotiatedEncryptionPolicy, peer);
        }

        int theirPadding = in.getShort() & 0xFFFF;
        if (theirPadding > 512) {
            // sanity check
            throw new IllegalStateException("Padding is too long: " + theirPadding);
        }
        int position = in.position(); // mark
        // check if the whole padding block has already arrived
        if (in.remaining() < theirPadding) {
            in.limit(in.capacity());
            in.position(phase1Read + phase2Read);
            encryptedReader.readAtLeast(theirPadding - in.remaining() + 2/*IA length*/).read(in);
            in.flip();
            in.position(position); // reset
        }

        in.position(position + theirPadding); // discard padding
        // Initial Payload length (0..65535 bytes)
        int initialPayloadLength = in.getShort() & 0xFFFF; // currently we ignore IA, it will be processed by the upper layer
        in.compact();

        // 4. B->A:
        // - ENCRYPT(VC, crypto_select, len(padD), padD)
        // - ENCRYPT2(Payload Stream)
        out.put(VC_RAW_BYTES);
        out.put(getCryptoProvideBitfield(negotiatedEncryptionPolicy));
        byte[] padding = getZeroPadding(512);
        out.putShort((short) padding.length);
        out.put(padding);
        out.flip();
        encryptedChannel.write(out);
        out.clear();

        switch (negotiatedEncryptionPolicy) {
            case REQUIRE_PLAINTEXT:
            case PREFER_PLAINTEXT: {
                return createReaderWriter(peer, channel, in, out);
            }
            case PREFER_ENCRYPTED:
            case REQUIRE_ENCRYPTED: {
                return createReaderWriter(peer, encryptedChannel, in, out);
            }
            default: {
                throw new IllegalStateException("Unknown encryption policy: " + negotiatedEncryptionPolicy.name());
            }
        }
    }

    private ByteChannelReader reader(ReadableByteChannel channel) {
        return ByteChannelReader.forChannel(channel).withTimeout(receiveTimeout).waitBetweenReads(waitBetweenReads);
    }

    private void assertPolicyIsCompatible(EncryptionPolicy peerEncryptionPolicy) {
        if (!localEncryptionPolicy.isCompatible(peerEncryptionPolicy)) {
            throw new RuntimeException("Encryption policies are incompatible: peer's (" + peerEncryptionPolicy.name()
                    + "), local (" + localEncryptionPolicy.name() + ")");
        }
    }

    private MessageReaderWriter createReaderWriter(Peer peer, ByteChannel channel, ByteBuffer in, ByteBuffer out) {
        Supplier<Message> reader = new DefaultMessageReader(peer, channel, messageHandler, in);
        Consumer<Message> writer = new DefaultMessageWriter(channel, messageHandler, out);
        return new DelegatingMessageReaderWriter(reader, writer);
    }

    private byte[] getPadding(int length) {
        // todo: use this constructor everywhere in the project
        Random r = new Random();
        byte[] padding = new byte[r.nextInt(length + 1)];
        for (int i = 0; i < padding.length; i++) {
            padding[i] = (byte) r.nextInt(256);
        }
        return padding;
    }

    private byte[] getZeroPadding(int length) {
        // todo: use this constructor everywhere in the project
        Random r = new Random();
        return new byte[r.nextInt(length + 1)];
    }

    private MessageDigest getDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] xor(byte[] b1, byte[] b2) {
        if (b1.length != b2.length) {
            throw new IllegalStateException("Lengths do not match: " + b1.length + ", " + b2.length);
        }
        byte[] result = new byte[b1.length];
        for (int i = 0; i < b1.length; i++) {
            result[i] = (byte) (b1[i] ^ b2[i]);
        }
        return result;
    }

    private byte[] getCryptoProvideBitfield(EncryptionPolicy encryptionPolicy) {
        byte[] crypto_provide = new byte[4];
        switch (encryptionPolicy) {
            case REQUIRE_PLAINTEXT: {
                crypto_provide[3] = 1; // only 0x01
                break;
            }
            case PREFER_PLAINTEXT:
            case PREFER_ENCRYPTED: {
                crypto_provide[3] = 3; // both 0x01 and 0x02
                break;
            }
            case REQUIRE_ENCRYPTED: {
                crypto_provide[3] = 2; // only 0x02
                break;
            }
            default: {
                // do nothing, bitfield is all zeros
            }
        }
        return crypto_provide;
    }

    private EncryptionPolicy selectPolicy(byte[] crypto_provide, EncryptionPolicy localEncryptionPolicy) {
        boolean plaintextProvided = (crypto_provide[3] & 0x01) == 0x01;
        boolean encryptionProvided = (crypto_provide[3] & 0x02) == 0x02;

        EncryptionPolicy selected = null;
        if (plaintextProvided || encryptionProvided) {
            switch (localEncryptionPolicy) {
                case REQUIRE_PLAINTEXT: {
                    if (plaintextProvided) {
                        selected = EncryptionPolicy.REQUIRE_PLAINTEXT;
                    }
                    break;
                }
                case PREFER_PLAINTEXT: {
                    selected = plaintextProvided ? EncryptionPolicy.REQUIRE_PLAINTEXT : EncryptionPolicy.REQUIRE_ENCRYPTED;
                    break;
                }
                case PREFER_ENCRYPTED: {
                    selected = encryptionProvided ? EncryptionPolicy.REQUIRE_ENCRYPTED : EncryptionPolicy.REQUIRE_PLAINTEXT;
                    break;
                }
                case REQUIRE_ENCRYPTED: {
                    if (encryptionProvided) {
                        selected = EncryptionPolicy.REQUIRE_ENCRYPTED;
                    }
                    break;
                }
                default: {
                    throw new IllegalStateException("Unknown encryption policy: " + localEncryptionPolicy.name());
                }
            }
        }

        if (selected == null) {
            throw new IllegalStateException("Failed to negotiate the encryption policy: local policy (" +
                    localEncryptionPolicy.name() + "), peer's policy (" + Arrays.toString(crypto_provide) + ")");
        }
        return selected;
    }
}
