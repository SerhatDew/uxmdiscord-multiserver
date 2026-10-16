package com.uxplima.discordsync.multiserver.discord;

import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft Server List Ping protocol query to proxy (Velocity/BungeeCord).
 */
public final class ProxyPlayerCount {
    private ProxyPlayerCount() {}

    public static int read(String host, int port, int timeout) throws IOException {
        try (Socket socket = new Socket(java.net.Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(buffer);

            writeVarInt(handshake, 0); // packet id 0 (handshake)
            writeVarInt(handshake, -1); // protocol version
            byte[] address = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, address.length);
            handshake.write(address);
            handshake.writeShort(port);
            writeVarInt(handshake, 1); // next state 1 (status)

            writeVarInt(output, buffer.size());
            output.write(buffer.toByteArray());
            output.write(new byte[]{1, 0}); // status request packet
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = readVarInt(input);
            if (length < 2 || length > 1048576) {
                throw new IOException("Invalid proxy status packet length: " + length);
            }

            byte[] packet = input.readNBytes(length);
            if (packet.length != length) {
                throw new EOFException("Incomplete proxy status packet");
            }

            DataInputStream response = new DataInputStream(new ByteArrayInputStream(packet));
            if (readVarInt(response) != 0) {
                throw new IOException("Unexpected proxy packet id");
            }

            int jsonLength = readVarInt(response);
            if (jsonLength < 1 || jsonLength != response.available()) {
                throw new IOException("Invalid proxy status JSON length");
            }

            try {
                String jsonStr = new String(response.readNBytes(jsonLength), StandardCharsets.UTF_8);
                int count = JsonParser.parseString(jsonStr)
                        .getAsJsonObject().getAsJsonObject("players").get("online").getAsInt();
                if (count < 0) {
                    throw new IOException("Negative online count");
                }
                return count;
            } catch (RuntimeException error) {
                throw new IOException("Invalid proxy status JSON", error);
            }
        }
    }

    public static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            int value = input.readUnsignedByte();
            result |= (value & 127) << (i * 7);
            if ((value & 128) == 0) {
                return result;
            }
        }
        throw new IOException("VarInt exceeds five bytes");
    }

    public static void writeVarInt(DataOutputStream output, int value) throws IOException {
        do {
            int part = value & 127;
            value >>>= 7;
            output.writeByte(value == 0 ? part : part | 128);
        } while (value != 0);
    }
}
