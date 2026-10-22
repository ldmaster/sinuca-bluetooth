package com.ldmaster.sinuca.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

class BluetoothGame {

    interface Listener {
        fun onConnected(peer: String)
        fun onMessage(message: NetMessage)
        fun onDisconnected(reason: String)
    }

    @Volatile
    var listener: Listener? = null

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var running = true
    @Volatile
    private var reported = false
    @Volatile
    private var connection: Connection? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null

    @SuppressLint("MissingPermission")
    fun startHost() {
        if (!running) return
        val socket = try {
            adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (error: SecurityException) {
            notifyDisconnected("Permissão de Bluetooth negada.")
            return
        } catch (error: IOException) {
            notifyDisconnected("Não foi possível abrir o servidor Bluetooth.")
            return
        }
        if (socket == null) {
            notifyDisconnected("Bluetooth indisponível neste aparelho.")
            return
        }
        acceptThread = AcceptThread(socket).also { it.start() }
    }

    @SuppressLint("MissingPermission")
    fun startClient(device: BluetoothDevice) {
        if (!running) return
        adapter?.cancelDiscovery()
        connectThread = ConnectThread(device).also { it.start() }
    }

    fun send(line: String) {
        connection?.write(line)
    }

    fun close() {
        running = false
        acceptThread?.cancel()
        connectThread?.cancel()
        connection?.cancel()
        acceptThread = null
        connectThread = null
        connection = null
    }

    private fun attach(socket: BluetoothSocket) {
        if (!running) {
            runCatching { socket.close() }
            return
        }
        val peer = try {
            socket.remoteDevice?.name ?: "adversário"
        } catch (error: SecurityException) {
            "adversário"
        }
        val thread = Connection(socket)
        connection = thread
        thread.start()
        main.post { listener?.onConnected(peer) }
    }

    private fun notifyDisconnected(reason: String) {
        if (!running || reported) return
        reported = true
        main.post { listener?.onDisconnected(reason) }
    }

    private inner class AcceptThread(
        private val server: BluetoothServerSocket
    ) : Thread("sinuca-accept") {

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                val socket = server.accept()
                if (socket != null) attach(socket)
            } catch (error: IOException) {
                if (running) Log.i(TAG, "accept encerrado: ${error.message}")
            } finally {
                runCatching { server.close() }
            }
        }

        fun cancel() {
            runCatching { server.close() }
        }
    }

    private inner class ConnectThread(
        private val device: BluetoothDevice
    ) : Thread("sinuca-connect") {

        private var socket: BluetoothSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                val candidate = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = candidate
                candidate.connect()
                attach(candidate)
            } catch (error: IOException) {
                runCatching { socket?.close() }
                notifyDisconnected("Não foi possível conectar. Verifique o pareamento e tente de novo.")
            } catch (error: SecurityException) {
                runCatching { socket?.close() }
                notifyDisconnected("Permissão de Bluetooth negada.")
            }
        }

        fun cancel() {
            runCatching { socket?.close() }
        }
    }

    private inner class Connection(private val socket: BluetoothSocket) : Thread("sinuca-io") {

        private val input: BufferedReader = BufferedReader(InputStreamReader(socket.inputStream))
        private val output: OutputStream = socket.outputStream

        override fun run() {
            var reason = "O adversário encerrou a conexão."
            try {
                while (running) {
                    val line = input.readLine() ?: break
                    val message = Wire.parse(line) ?: continue
                    main.post { listener?.onMessage(message) }
                }
            } catch (error: IOException) {
                reason = "Conexão Bluetooth perdida."
            } finally {
                cancel()
            }
            notifyDisconnected(reason)
        }

        fun write(line: String) {
            try {
                synchronized(output) {
                    output.write((line + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (error: IOException) {
                notifyDisconnected("Falha ao enviar dados pelo Bluetooth.")
            }
        }

        fun cancel() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "BluetoothGame"
        private const val SERVICE_NAME = "SinucaBluetooth"
        val SERVICE_UUID: UUID = UUID.fromString("8f3e1c2a-5b7d-4e9f-9a10-3c2b1d4e5f60")
    }
}
