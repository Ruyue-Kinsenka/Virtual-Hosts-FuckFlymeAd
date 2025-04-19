package com.github.xfalcon.vhosts.vservice;

import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VPNRunnable implements Runnable {
    private static final String TAG = VPNRunnable.class.getSimpleName();

    private FileDescriptor vpnFileDescriptor;

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

    public VPNRunnable(FileDescriptor vpnFileDescriptor,
                       ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                       ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                       ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
        this.vpnFileDescriptor = vpnFileDescriptor;
        this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
        this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }

    @Override
    public void run() {
        LogUtils.i(TAG, "Started");

        FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
        FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();
        try {
            ByteBuffer bufferToNetwork = null;
            boolean dataSent = true;
            boolean dataReceived;
            while (!Thread.interrupted()) {
                if (dataSent)
                    bufferToNetwork = ByteBufferPool.acquire();
                else
                    bufferToNetwork.clear();

                // TODO: Block when not connected
                int readBytes = vpnInput.read(bufferToNetwork);
                if (readBytes > 0) {
                    dataSent = true;
                    bufferToNetwork.flip();
                    Packet packet = new Packet(bufferToNetwork);
                    if (packet.isUDP()) {
                        deviceToNetworkUDPQueue.offer(packet);
                    } else if (packet.isTCP()) {
                        deviceToNetworkTCPQueue.offer(packet);
                    } else {
                        LogUtils.w(TAG, "Unknown packet type");
                        dataSent = false;
                    }
                } else {
                    dataSent = false;
                }
                ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                if (bufferFromNetwork != null) {
                    bufferFromNetwork.flip();
                    while (bufferFromNetwork.hasRemaining())
                        try {
                            vpnOutput.write(bufferFromNetwork);
                        } catch (Exception e) {
                            LogUtils.e(TAG, e.toString(), e);
                            break;
                        }
                    dataReceived = true;
                    ByteBufferPool.release(bufferFromNetwork);
                } else {
                    dataReceived = false;
                }

                // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                if (!dataSent && !dataReceived)
                    Thread.sleep(11);
            }
        } catch (InterruptedException e) {
            LogUtils.i(TAG, "Stopping");
        } catch (IOException e) {
            LogUtils.w(TAG, e.toString(), e);
        } finally {
            closeResources(vpnInput, vpnOutput);
        }
    }
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                LogUtils.e(TAG, e.toString(), e);
            }
        }
    }
}
