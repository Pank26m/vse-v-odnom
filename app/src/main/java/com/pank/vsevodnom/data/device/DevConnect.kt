package com.pank.vsevodnom.data.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** Сеть, Wi-Fi, Bluetooth, NFC, UWB, SIM и соты, GNSS. Всё — с телефона; закрытое без разрешения просто не показывается. */
@SuppressLint("MissingPermission")
object DevConnect {
    fun ipv4(i: Int): String = "${i and 0xff}.${i shr 8 and 0xff}.${i shr 16 and 0xff}.${i ushr 24}"

    fun channelOf(freq: Int) = when {
        freq == 2484 -> 14; freq in 2412..2472 -> (freq - 2407) / 5; freq in 5000..5895 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5; else -> 0
    }
    fun band(freq: Int) = when { freq < 3000 -> "2,4 ГГц"; freq < 5925 -> "5 ГГц"; freq < 7200 -> "6 ГГц"; else -> "60 ГГц" }
    fun widthMhz(w: Int) = when (w) { ScanResult.CHANNEL_WIDTH_20MHZ -> 20; ScanResult.CHANNEL_WIDTH_40MHZ -> 40; ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160; 5 -> 320; else -> 20 }
    fun standard(s: Int) = when (s) { 1 -> "Wi-Fi (802.11a/b/g)"; 4 -> "Wi-Fi 4 (802.11n)"; 5 -> "Wi-Fi 5 (802.11ac)"; 6 -> "Wi-Fi 6 (802.11ax)"; 7 -> "WiGig (802.11ad)"; 8 -> "Wi-Fi 7 (802.11be)"; else -> null }
    fun security(caps: String) = when {
        caps.contains("SAE") && caps.contains("PSK") -> "WPA2/WPA3"; caps.contains("SAE") -> "WPA3"; caps.contains("OWE") -> "Enhanced Open (OWE)"
        caps.contains("EAP") -> "WPA-Enterprise"; caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"; caps.contains("WPA") -> "WPA"; caps.contains("WEP") -> "WEP"; else -> "открытая"
    }

    fun network(ctx: Context): List<InfoSection> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val out = ArrayList<InfoSection>()
        val net = cm.activeNetwork; val nc = net?.let { cm.getNetworkCapabilities(it) }; val lp = net?.let { cm.getLinkProperties(it) }
        out += section("Подключение") {
            add("Тип", when {
                nc == null -> "нет сети"; nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"; nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"; nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"; else -> "другая" })
            nc?.let {
                flag("Интернет проверен", it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                flag("Безлимитная (не тарифицируется)", it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                add("Оценка скорости", "↓ ${it.linkDownstreamBandwidthKbps / 1000} Мбит/с · ↑ ${it.linkUpstreamBandwidthKbps / 1000} Мбит/с")
                if (Build.VERSION.SDK_INT >= 29) it.signalStrength.takeIf { s -> s != Int.MIN_VALUE }?.let { s -> add("Сигнал", "$s дБм") }
            }
            lp?.let { l ->
                add("Интерфейс", l.interfaceName)
                l.linkAddresses.forEach { a -> add(if (a.address is Inet6Address) "IPv6" else "IPv4", "${a.address.hostAddress}/${a.prefixLength}") }
                add("Шлюз", l.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress)
                add("DNS", l.dnsServers.joinToString(", ") { it.hostAddress ?: "" })
                if (Build.VERSION.SDK_INT >= 28) add("Частный DNS", if (l.isPrivateDnsActive) (l.privateDnsServerName ?: "автоматически") else "выключен")
                add("Домен", l.domains)
                if (Build.VERSION.SDK_INT >= 29) l.mtu.takeIf { it > 0 }?.let { add("MTU", it) }
                l.httpProxy?.let { add("Прокси", "${it.host}:${it.port}") }
            }
            add("Режим экономии трафика", when (cm.restrictBackgroundStatus) { ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "включён"; ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "включён (нам можно)"; else -> "выключен" })
        }
        if (wm.isWifiEnabled) {
            @Suppress("DEPRECATION") val wi = wm.connectionInfo
            @Suppress("DEPRECATION") val dh = wm.dhcpInfo
            val sr = runCatching { wm.scanResults }.getOrNull().orEmpty().firstOrNull { it.BSSID == wi?.bssid }
            out += section("Wi-Fi") {
                if (wi != null && wi.networkId != -1) {
                    add("Сеть", wi.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "скрыто (нужен доступ к местоположению)")
                    add("BSSID (точка)", wi.bssid?.takeIf { it != "02:00:00:00:00:00" })
                    if (Build.VERSION.SDK_INT >= 30) add("Стандарт", standard(wi.wifiStandard))
                    add("Частота", "${wi.frequency} МГц · ${band(wi.frequency)} · канал ${channelOf(wi.frequency)}")
                    sr?.let { add("Ширина канала", "${widthMhz(it.channelWidth)} МГц"); add("Защита", security(it.capabilities)) }
                    if (Build.VERSION.SDK_INT >= 31) add("Тип защиты", when (wi.currentSecurityType) { 0 -> "открытая"; 1 -> "WEP"; 2 -> "WPA/WPA2-PSK"; 3 -> "Enterprise"; 4 -> "WPA3-SAE"; 6 -> "OWE"; else -> null })
                    add("Сигнал", "${wi.rssi} дБм · ${WifiManager.calculateSignalLevel(wi.rssi, 100)} %")
                    add("Скорость связи", "${wi.linkSpeed} Мбит/с" + (if (Build.VERSION.SDK_INT >= 29) " (↑ ${wi.txLinkSpeedMbps} / ↓ ${wi.rxLinkSpeedMbps})" else ""))
                    if (Build.VERSION.SDK_INT >= 30) add("Макс. скорость", "↑ ${wi.maxSupportedTxLinkSpeedMbps} / ↓ ${wi.maxSupportedRxLinkSpeedMbps} Мбит/с")
                    add("MAC телефона", wi.macAddress?.takeIf { it != "02:00:00:00:00:00" }, "случайный для каждой сети")
                }
                dh?.let { d ->
                    if (d.ipAddress != 0) add("IP (DHCP)", ipv4(d.ipAddress))
                    if (d.gateway != 0) add("Шлюз (роутер)", ipv4(d.gateway))
                    if (d.netmask != 0) add("Маска", ipv4(d.netmask))
                    if (d.serverAddress != 0) add("DHCP-сервер", ipv4(d.serverAddress))
                    if (d.leaseDuration > 0) add("Аренда адреса", Dev.dur(d.leaseDuration.toLong()))
                    if (d.dns1 != 0) add("DNS 1", ipv4(d.dns1)); if (d.dns2 != 0) add("DNS 2", ipv4(d.dns2))
                }
            }
        }
        out += section("Сетевые интерфейсы") {
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList()).filter { it.isUp }.forEach { ni ->
                add(ni.name, ni.inetAddresses.toList().joinToString(", ") { (if (it is Inet4Address) "" else "") + (it.hostAddress?.substringBefore('%') ?: "") } +
                    (runCatching { " · MTU ${ni.mtu}" }.getOrDefault("")))
            }
        }
        return out
    }

    fun wifiCaps(ctx: Context): InfoSection {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val pm = ctx.packageManager
        return section("Возможности Wi-Fi") {
            if (Build.VERSION.SDK_INT >= 30) {
                val best = listOf(8 to "Wi-Fi 7 (802.11be)", 6 to "Wi-Fi 6/6E (802.11ax)", 5 to "Wi-Fi 5 (802.11ac)", 4 to "Wi-Fi 4 (802.11n)")
                    .firstOrNull { runCatching { wm.isWifiStandardSupported(it.first) }.getOrDefault(false) }?.second
                add("Максимальный стандарт", best)
                add("Стандарты", listOf(1 to "a/b/g", 4 to "n", 5 to "ac", 6 to "ax", 8 to "be").filter { runCatching { wm.isWifiStandardSupported(it.first) }.getOrDefault(false) }.joinToString(", ") { "802.11" + it.second })
            }
            flag("5 ГГц", wm.is5GHzBandSupported)
            if (Build.VERSION.SDK_INT >= 30) flag("6 ГГц", wm.is6GHzBandSupported)
            if (Build.VERSION.SDK_INT >= 31) flag("60 ГГц", wm.is60GHzBandSupported)
            flag("WPA3 (SAE)", wm.isWpa3SaeSupported); flag("WPA3 Enterprise 192 бит", wm.isWpa3SuiteBSupported); flag("Enhanced Open (OWE)", wm.isEnhancedOpenSupported)
            flag("Easy Connect (QR)", wm.isEasyConnectSupported); flag("Wi-Fi Direct", wm.isP2pSupported); flag("Wi-Fi Aware", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
            flag("Измерение расстояния (RTT)", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)); flag("Passpoint (Hotspot 2.0)", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT))
            flag("Точка доступа и Wi-Fi одновременно", wm.isStaApConcurrencySupported); flag("TDLS", wm.isTdlsSupported)
            if (Build.VERSION.SDK_INT >= 31) flag("Две сети одновременно", wm.isStaConcurrencyForLocalOnlyConnectionsSupported)
            if (Build.VERSION.SDK_INT >= 30) flag("Ограничение частоты сканирования", wm.isScanThrottleEnabled)
            if (Build.VERSION.SDK_INT >= 30) add("Уровней сигнала", wm.maxSignalLevel + 1)
        }
    }

    fun bluetooth(ctx: Context): List<InfoSection> {
        val ba = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return listOf(section("Bluetooth") { add("Адаптер", "нет") })
        val pm = ctx.packageManager
        val out = ArrayList<InfoSection>()
        val leAudio = if (Build.VERSION.SDK_INT >= 33) ba.isLeAudioSupported == BluetoothStatusCodes_SUPPORTED else false
        out += section("Bluetooth") {
            add("Состояние", if (ba.isEnabled) "включён" else "выключен")
            runCatching { add("Имя", ba.name) }
            add("Версия (по функциям)", when { leAudio -> "5.2 или новее"; ba.isLe2MPhySupported || ba.isLeExtendedAdvertisingSupported -> "5.0 или новее"; pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "4.x"; else -> null })
            flag("Bluetooth LE", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            flag("Высокая скорость (LE 2M PHY)", ba.isLe2MPhySupported); flag("Дальняя связь (LE Coded PHY)", ba.isLeCodedPhySupported)
            flag("Расширенные объявления LE", ba.isLeExtendedAdvertisingSupported); flag("Периодические объявления LE", ba.isLePeriodicAdvertisingSupported)
            add("Макс. длина данных объявления", ba.leMaximumAdvertisingDataLength.takeIf { it > 0 })
            flag("Несколько объявлений", ba.isMultipleAdvertisementSupported); flag("Аппаратная фильтрация", ba.isOffloadedFilteringSupported)
            flag("Пакетное сканирование", ba.isOffloadedScanBatchingSupported)
            if (Build.VERSION.SDK_INT >= 33) {
                flag("LE Audio", leAudio)
                flag("LE Audio: трансляция (Auracast)", ba.isLeAudioBroadcastSourceSupported == BluetoothStatusCodes_SUPPORTED)
                flag("LE Audio: помощник трансляции", ba.isLeAudioBroadcastAssistantSupported == BluetoothStatusCodes_SUPPORTED)
                runCatching { add("Аудиоустройств одновременно", ba.maxConnectedAudioDevices) }
            }
            runCatching { add("Подключён профиль A2DP", stateName(ba.getProfileConnectionState(BluetoothProfile.A2DP))) }
            runCatching { add("Подключён профиль гарнитуры", stateName(ba.getProfileConnectionState(BluetoothProfile.HEADSET))) }
        }
        runCatching {
            val bonded = ba.bondedDevices.orEmpty()
            if (bonded.isNotEmpty()) out += InfoSection("Сопряжённые устройства (${bonded.size})", bonded.map { d ->
                InfoRow(d.name ?: d.address, listOfNotNull(when (d.type) { 1 -> "классический"; 2 -> "LE"; 3 -> "двойной"; else -> null },
                    btClass(d.bluetoothClass?.majorDeviceClass), d.address).joinToString(" · "))
            })
        }
        return out
    }
    private const val BluetoothStatusCodes_SUPPORTED = 10
    private fun stateName(s: Int) = when (s) { BluetoothProfile.STATE_CONNECTED -> "да"; BluetoothProfile.STATE_CONNECTING -> "подключается"; else -> "нет" }
    private fun btClass(c: Int?) = when (c) { 0x0100 -> "компьютер"; 0x0200 -> "телефон"; 0x0400 -> "аудио"; 0x0500 -> "периферия"; 0x0600 -> "изображение"; 0x0700 -> "носимое"; 0x0900 -> "здоровье"; else -> null }

    fun nfcUwb(ctx: Context): InfoSection {
        val pm = ctx.packageManager
        val nfc = runCatching { NfcAdapter.getDefaultAdapter(ctx) }.getOrNull()
        return section("NFC, UWB и прочее") {
            flag("NFC", nfc != null)
            nfc?.let { add("NFC сейчас", if (it.isEnabled) "включён" else "выключен"); if (Build.VERSION.SDK_INT >= 29) { flag("Безопасный NFC", it.isSecureNfcSupported); if (it.isSecureNfcSupported) add("Безопасный NFC сейчас", if (it.isSecureNfcEnabled) "включён" else "выключен") } }
            flag("Оплата телефоном (HCE)", pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            flag("Встроенный защищённый элемент (eSE)", pm.hasSystemFeature("android.hardware.se.omapi.ese"))
            flag("Защищённый элемент на SIM", pm.hasSystemFeature("android.hardware.se.omapi.uicc"))
            flag("UWB (сверхширокополосная)", pm.hasSystemFeature("android.hardware.uwb"))
            flag("ИК-порт", pm.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR))
            flag("Thread", pm.hasSystemFeature("android.hardware.thread_network"))
            flag("Спутниковая связь", pm.hasSystemFeature("android.hardware.telephony.satellite"))
        }
    }

    fun gnss(ctx: Context): InfoSection {
        val lm = ctx.getSystemService(LocationManager::class.java)
        return section("Навигация (GNSS)") {
            if (Build.VERSION.SDK_INT >= 28) { add("Чип", lm.gnssHardwareModelName); add("Год железа", lm.gnssYearOfHardware.takeIf { it > 0 }) }
            add("Геолокация", if (lm.isLocationEnabled) "включена" else "выключена")
            add("Источники", lm.allProviders.joinToString(", "))
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val c = lm.gnssCapabilities
                flag("Сырые измерения", c.hasMeasurements()); flag("Навигационные сообщения", c.hasNavigationMessages())
                if (Build.VERSION.SDK_INT >= 31) { flag("Данные антенны", c.hasAntennaInfo()); flag("Коррекции измерений", c.hasMeasurementCorrections()); flag("Режим низкого потребления", c.hasLowPowerMode()); flag("Блок-лист спутников", c.hasSatelliteBlocklist()) }
            }
            flag("Двухчастотный приём (L5) — сырые измерения", ctx.packageManager.hasSystemFeature("android.hardware.location.gps"))
        }
    }

    fun telephony(ctx: Context): List<InfoSection> {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val out = ArrayList<InfoSection>()
        out += section("Мобильная связь") {
            add("Тип телефона", when (tm.phoneType) { TelephonyManager.PHONE_TYPE_GSM -> "GSM"; TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"; TelephonyManager.PHONE_TYPE_SIP -> "SIP"; else -> "нет" })
            if (Build.VERSION.SDK_INT >= 30) add("Слотов SIM", "${tm.activeModemCount} активно из ${tm.supportedModemCount}")
            flag("eSIM", ctx.packageManager.hasSystemFeature("android.hardware.telephony.euicc"))
            runCatching { add("eSIM включена", if ((ctx.getSystemService(android.telephony.euicc.EuiccManager::class.java))?.isEnabled == true) "да" else "нет") }
            flag("Голосовые звонки", tm.isVoiceCapable); flag("СМС", tm.isSmsCapable)
            add("Оператор сети", tm.networkOperatorName); add("Код сети (MCC/MNC)", tm.networkOperator); add("Страна сети", tm.networkCountryIso.uppercase())
            add("Роуминг", if (tm.isNetworkRoaming) "да" else "нет")
            runCatching { add("Тип сети данных", netType(tm.dataNetworkType)) }
            runCatching { add("Тип сети голоса", netType(tm.voiceNetworkType)) }
            runCatching { add("Мобильные данные", if (tm.isDataEnabled) "включены" else "выключены") }
            if (Build.VERSION.SDK_INT >= 28) tm.signalStrength?.let { s ->
                add("Уровень сигнала", "${s.level} из 4")
                s.cellSignalStrengths.forEach { c -> add("Сигнал " + c.javaClass.simpleName.removePrefix("CellSignalStrength"), "${c.dbm} дБм · ASU ${c.asuLevel}" + when (c) {
                    is CellSignalStrengthLte -> " · RSRP ${c.rsrp} · RSRQ ${c.rsrq} · SNR ${c.rssnr}"; is CellSignalStrengthNr -> " · SS-RSRP ${c.ssRsrp} · SS-RSRQ ${c.ssRsrq} · SINR ${c.ssSinr}"; else -> "" }) }
            }
        }
        runCatching {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList?.forEach { s ->
                out += section("SIM ${s.simSlotIndex + 1}: ${s.displayName}") {
                    add("Оператор", s.carrierName); add("Страна", s.countryIso.uppercase())
                    if (Build.VERSION.SDK_INT >= 29) add("MCC / MNC", "${s.mccString} / ${s.mncString}")
                    add("Тип", if (s.isEmbedded) "eSIM" else "физическая")
                    runCatching { add("Номер", if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(s.subscriptionId) else @Suppress("DEPRECATION") s.number) }
                    add("Роль", listOfNotNull(if (s.subscriptionId == SubscriptionManager.getDefaultDataSubscriptionId()) "данные" else null,
                        if (s.subscriptionId == SubscriptionManager.getDefaultVoiceSubscriptionId()) "звонки" else null,
                        if (s.subscriptionId == SubscriptionManager.getDefaultSmsSubscriptionId()) "СМС" else null).joinToString(", ").ifEmpty { "—" })
                    runCatching { tm.createForSubscriptionId(s.subscriptionId).let { t -> add("Состояние SIM", simState(t.simState)); add("Сеть", netType(t.dataNetworkType)) } }
                }
            }
        }
        runCatching {
            val cells = tm.allCellInfo.orEmpty()
            if (cells.isNotEmpty()) out += InfoSection("Соты рядом (${cells.size})", cells.sortedByDescending { it.isRegistered }.take(20).map { cellRow(it) },
                "★ — сота, к которой подключён телефон")
        }
        return out
    }
    private fun cellRow(c: CellInfo): InfoRow {
        val reg = if (c.isRegistered) "★ " else ""
        return when (c) {
            is CellInfoLte -> c.cellIdentity.let { id -> InfoRow("${reg}LTE ${id.mobileNetworkOperator ?: ""}", listOfNotNull("PCI ${id.pci}", "TAC ${id.tac}", "EARFCN ${id.earfcn}",
                if (Build.VERSION.SDK_INT >= 30) id.bands.takeIf { it.isNotEmpty() }?.joinToString(",", "B") else null, id.bandwidth.takeIf { it in 1..100000 }?.let { "${it / 1000} МГц" }, "${c.cellSignalStrength.dbm} дБм").joinToString(" · ")) }
            is CellInfoNr -> (c.cellIdentity as android.telephony.CellIdentityNr).let { id -> InfoRow("${reg}5G NR ${id.mccString.orEmpty()}${id.mncString.orEmpty()}", listOfNotNull("PCI ${id.pci}", "ARFCN ${id.nrarfcn}",
                if (Build.VERSION.SDK_INT >= 30) id.bands.takeIf { it.isNotEmpty() }?.joinToString(",", "n") else null, "${c.cellSignalStrength.dbm} дБм").joinToString(" · ")) }
            is CellInfoWcdma -> c.cellIdentity.let { id -> InfoRow("${reg}3G WCDMA", "PSC ${id.psc} · UARFCN ${id.uarfcn} · ${c.cellSignalStrength.dbm} дБм") }
            is CellInfoGsm -> c.cellIdentity.let { id -> InfoRow("${reg}2G GSM", "LAC ${id.lac} · ARFCN ${id.arfcn} · ${c.cellSignalStrength.dbm} дБм") }
            else -> InfoRow(reg + c.javaClass.simpleName, "${c.cellSignalStrength.dbm} дБм")
        }
    }
    fun netType(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"; TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"; TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi звонки (IWLAN)"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"; TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"; TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"; TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "нет"; else -> "тип $t"
    }
    private fun simState(s: Int) = when (s) { TelephonyManager.SIM_STATE_READY -> "готова"; TelephonyManager.SIM_STATE_ABSENT -> "нет"; TelephonyManager.SIM_STATE_PIN_REQUIRED -> "нужен PIN"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "нужен PUK"; TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "заблокирована оператором"; else -> "$s" }

    /** Публичный пример: какой у меня интернет сейчас (для сводки). */
    fun shortNet(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val nc = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "нет сети"
        return when {
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION") val wi = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
                "Wi-Fi · ${band(wi.frequency)} · ${wi.linkSpeed} Мбит/с · ${wi.rssi} дБм"
            }
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная · " + runCatching { netType(ctx.getSystemService(TelephonyManager::class.java).dataNetworkType) }.getOrDefault("")
            else -> "подключено"
        }
    }
}
