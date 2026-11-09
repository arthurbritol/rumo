package app.hunter.podble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import app.hunter.pod.PodConstants
import app.hunter.pod.PodPacket
import app.hunter.pod.session.PodTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PodDevice(val address: String, val name: String?)

/**
 * Transporte BLE do Pod (PROTOCOL.md §1): o celular é cliente GATT do Pod *e* servidor GATT local com o mesmo
 * serviço/característica (o Pod escreve as respostas por ele; não há advertising). Escreve com
 * WRITE_NO_RESPONSE quando disponível e só considera concluída a escrita em `onCharacteristicWrite`.
 *
 * ATENÇÃO: portado do comportamento documentado do WazeTripper (testado só no Pod da Meteor 350). Nunca foi
 * executado em hardware neste projeto — validar com a Hunter 350 (teste de bancada).
 */
@SuppressLint("MissingPermission") // as permissões BLUETOOTH_* são pedidas pela UI antes de conectar
class BlePodLink(context: Context) : PodTransport {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var server: BluetoothGattServer? = null

    private var ready: CompletableDeferred<Unit>? = null
    private var writeWaiter: CompletableDeferred<Int>? = null

    /** Chamado quando o link cai (status 19 = o próprio Pod fechou por watchdog). */
    @Volatile
    var onDisconnected: ((status: Int) -> Unit)? = null

    /** Chamado com o que o Pod escreve de volta no nosso servidor GATT. */
    @Volatile
    var onIncoming: ((ByteArray) -> Unit)? = null

    /** Procura o `RE_DISP`, conecta, descobre o serviço e espera ~200 ms antes de liberar escritas. */
    suspend fun connect(scanTimeoutMs: Long = 20_000, connectTimeoutMs: Long = 15_000): PodDevice {
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) throw IOException("Bluetooth desligado ou indisponível")
        openServer()
        val device = withTimeout(scanTimeoutMs) { scan() }
        val r = CompletableDeferred<Unit>().also { ready = it }
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(connectTimeoutMs) { r.await() }
        delay(PodConstants.POST_DISCOVERY_DELAY_MS) // escrever antes disso faz o Pod derrubar o link
        return PodDevice(device.address, device.name)
    }

    private suspend fun scan(): BluetoothDevice = suspendCancellableCoroutine { cont ->
        val scanner = manager.adapter.bluetoothLeScanner
        if (scanner == null) {
            cont.resumeWithException(IOException("Scanner BLE indisponível"))
            return@suspendCancellableCoroutine
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                if (cont.isActive) cont.resume(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (cont.isActive) cont.resumeWithException(IOException("Scan falhou (código $errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setDeviceName(PodConstants.DEVICE_NAME).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
    }

    override suspend fun write(packet: ByteArray) {
        val g = gatt ?: throw IOException("sem conexão com o Pod")
        val ch = characteristic ?: throw IOException("característica do Pod não encontrada")
        val waiter = CompletableDeferred<Int>().also { writeWaiter = it }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, packet, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = packet
                g.writeCharacteristic(ch)
            }
        }
        if (!accepted) throw IOException("escrita recusada pela pilha BLE")
        val status = withTimeout(WRITE_TIMEOUT_MS) { waiter.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("escrita falhou (status $status)")
    }

    fun close() {
        onDisconnected = null
        writeWaiter?.completeExceptionally(IOException("fechado"))
        ready?.completeExceptionally(IOException("fechado"))
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        characteristic = null
        runCatching { server?.close() }
        server = null
    }

    // ---------- cliente GATT ----------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val err = IOException("desconectado (status $status)")
                ready?.completeExceptionally(err)
                writeWaiter?.completeExceptionally(err)
                runCatching { g.close() }
                if (gatt === g) gatt = null
                onDisconnected?.invoke(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(UUID.fromString(PodConstants.SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(PodConstants.CHARACTERISTIC_UUID))
            if (ch == null) {
                ready?.completeExceptionally(IOException("serviço do Pod não encontrado"))
                return
            }
            characteristic = ch
            writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            // O WazeTripper também liga notificações (CCCD) se existir; a característica do Pod não tem NOTIFY,
            // então é inofensivo quando ausente.
            g.setCharacteristicNotification(ch, true)
            ch.descriptors.firstOrNull()?.let { d ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    }
                }
            }
            ready?.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeWaiter?.complete(status)
        }
    }

    // ---------- servidor GATT local (canal de volta do Pod) ----------

    private fun openServer() {
        if (server != null) return
        val s = manager.openGattServer(appContext, serverCallback) ?: run {
            Log.w(TAG, "openGattServer retornou null")
            return
        }
        server = s
        val service = BluetoothGattService(UUID.fromString(PodConstants.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString(PodConstants.CHARACTERISTIC_UUID),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        s.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val bytes = value ?: ByteArray(0)
            Log.d(TAG, "← Pod: ${PodPacket.toHex(bytes)}")
            onIncoming?.invoke(bytes)
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0)) }
        }
    }

    private companion object {
        const val TAG = "BlePodLink"
        const val WRITE_TIMEOUT_MS = 3_000L
    }
}
