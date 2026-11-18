package com.example.admin

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// باشقۇرغۇچى رەڭ لايىھەسى
val AdminIndigo = Color(0xFF6366F1)
val AdminTurquoise = Color(0xFF06B6D4)
val AdminEmerald = Color(0xFF10B981)
val AdminRose = Color(0xFFF43F5E)
val AdminAmber = Color(0xFFF59E0B)
val AdminDarkBg = Color(0xFF070C1A)
val AdminCardSurface = Color(0xFF111C35)

const val ADMIN_MASTER_EMAIL = "uyghur162142@gmail.com"
const val SHARED_CRYPTO_SALT = "HESABAT_SYNC_2026_SECRET"

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // باشقا ئۈسكۈنە ياكى ئىز قوغلاش پىروگراممىلىرىنىڭ ئېكراننى تارتىۋېلىشىدىن قوغداش
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.decorView.filterTouchesWhenObscured = true

        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = AdminIndigo,
                        secondary = AdminTurquoise,
                        background = AdminDarkBg,
                        surface = AdminCardSurface,
                        onSurface = Color.White,
                        onBackground = Color.White
                    )
                ) {
                    AdminRootApp()
                }
            }
        }
    }
}

// خېرىدار ۋە ھېسابات مودېلى
data class ClientAccountRecord(
    val id: String,
    val fullName: String,
    val username: String,
    val password: String,
    val email: String,
    val streetName: String,
    val houseNumber: String,
    val flowAmount: String,
    val openDate: String,
    val notes: String,
    val status: String, // PENDING, APPROVED, VIP, FROZEN
    val licenseCode: String,
    val isVip: Boolean,
    val vipExpireEpochMillis: Long,
    val paidMonths: List<Boolean>
) {
    fun paidCount(): Int = paidMonths.count { it }
    fun isVipActive(): Boolean = isVip && (vipExpireEpochMillis == 0L || vipExpireEpochMillis > System.currentTimeMillis())
    fun vipRemainingDays(): Long {
        if (!isVip) return 0L
        if (vipExpireEpochMillis == 0L) return 9999L
        val diff = vipExpireEpochMillis - System.currentTimeMillis()
        return if (diff <= 0L) 0L else (diff / (1000L * 60L * 60L * 24L)) + 1L
    }
}

data class ClientFeedbackMessage(
    val id: String,
    val clientUsername: String,
    val clientName: String,
    val message: String,
    val adminReply: String,
    val timestamp: String
)

data class BroadcastReminder(
    val id: String,
    val title: String,
    val content: String,
    val targetUsername: String, // ALL ياكى مەخسۇس ھېسابات ئىسمى
    val timestamp: String
)

/**
 * باشقۇرغۇچى مەخسۇس ساقلاش ئامبىرى ۋە بۇلۇت ماس قەدەملەش ماتورى
 */
class AdminVaultStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("hesabat_admin_master_vault_v1", Context.MODE_PRIVATE)
    private val vaultFile: File
        get() = File(context.filesDir, "hesabat_admin_database.json")

    fun verifyAdminPassword(input: String): Boolean {
        val savedHash = prefs.getString("admin_pwd_hash", null) ?: sha256("162142")
        return sha256(input.trim()) == savedHash
    }

    fun updateAdminPassword(newPwd: String): Boolean {
        val clean = newPwd.trim()
        if (clean.length < 4) return false
        prefs.edit().putString("admin_pwd_hash", sha256(clean)).commit()
        return true
    }

    fun getCloudSyncUrl(): String {
        return prefs.getString("cloud_sync_url", "") ?: ""
    }

    fun setCloudSyncUrl(url: String) {
        prefs.edit().putString("cloud_sync_url", url.trim()).commit()
    }

    /**
     * ھەر بىر ھېسابات ئىسمىغا باغلاپ ئۆزگىچە دەلىللەش كودى ياساش.
     * بۇ كود خېرىدار ئەپىدە %100 ماس كېلىپ دەلىللىنىدۇ.
     */
    fun generateVerificationCode(username: String, isVip: Boolean = false): String {
        val clean = username.trim().lowercase()
        if (clean.isEmpty()) return ""
        val raw = sha256("$SHARED_CRYPTO_SALT::$clean").uppercase()
        val p1 = raw.substring(0, 4)
        val p2 = raw.substring(4, 8)
        val prefix = if (isVip) "VIP" else "HSB"
        return "$prefix-$p1-$p2"
    }

    fun loadAccounts(): List<ClientAccountRecord> {
        val rawText = runCatching {
            if (vaultFile.exists()) vaultFile.readText(Charsets.UTF_8) else ""
        }.getOrDefault("").ifBlank {
            prefs.getString("accounts_json", "") ?: ""
        }

        if (rawText.isBlank()) {
            val defaults = createInitialSampleAccounts()
            saveAccounts(defaults)
            return defaults
        }

        return try {
            val arr = JSONArray(rawText)
            val list = mutableListOf<ClientAccountRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mArr = o.optJSONArray("paidMonths")
                val months = List(12) { idx -> mArr?.optBoolean(idx, false) ?: false }
                list.add(
                    ClientAccountRecord(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        fullName = o.optString("fullName"),
                        username = o.optString("username"),
                        password = o.optString("password"),
                        email = o.optString("email"),
                        streetName = o.optString("streetName"),
                        houseNumber = o.optString("houseNumber"),
                        flowAmount = o.optString("flowAmount"),
                        openDate = o.optString("openDate"),
                        notes = o.optString("notes"),
                        status = o.optString("status", "APPROVED"),
                        licenseCode = o.optString("licenseCode"),
                        isVip = o.optBoolean("isVip", false),
                        vipExpireEpochMillis = o.optLong("vipExpireEpochMillis", 0L),
                        paidMonths = months
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(accounts: List<ClientAccountRecord>) {
        val arr = JSONArray()
        accounts.forEach { acc ->
            arr.put(
                JSONObject().apply {
                    put("id", acc.id)
                    put("fullName", acc.fullName)
                    put("username", acc.username)
                    put("password", acc.password)
                    put("email", acc.email)
                    put("streetName", acc.streetName)
                    put("houseNumber", acc.houseNumber)
                    put("flowAmount", acc.flowAmount)
                    put("openDate", acc.openDate)
                    put("notes", acc.notes)
                    put("status", acc.status)
                    put("licenseCode", acc.licenseCode)
                    put("isVip", acc.isVip)
                    put("vipExpireEpochMillis", acc.vipExpireEpochMillis)
                    put("paidMonths", JSONArray(acc.paidMonths))
                }
            )
        }
        val json = arr.toString()
        prefs.edit().putString("accounts_json", json).commit()
        runCatching { vaultFile.writeText(json, Charsets.UTF_8) }
    }

    fun loadFeedbackMessages(): List<ClientFeedbackMessage> {
        val raw = prefs.getString("feedback_json", "") ?: ""
        if (raw.isBlank()) {
            val sample = listOf(
                ClientFeedbackMessage(
                    id = "msg_1",
                    clientUsername = "alim01",
                    clientName = "ئالىمجان",
                    message = "ئەسسالامۇئەلەيكۇم باشقۇرغۇچى، مەن ھېسابات ئاچقان ئىدىم، دەلىللەش كودى ئەۋەتىپ بېرەمسىز؟",
                    adminReply = "",
                    timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                )
            )
            saveFeedbackMessages(sample)
            return sample
        }
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ClientFeedbackMessage(
                    id = o.optString("id"),
                    clientUsername = o.optString("clientUsername"),
                    clientName = o.optString("clientName"),
                    message = o.optString("message"),
                    adminReply = o.optString("adminReply"),
                    timestamp = o.optString("timestamp")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveFeedbackMessages(list: List<ClientFeedbackMessage>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("clientUsername", m.clientUsername)
                    put("clientName", m.clientName)
                    put("message", m.message)
                    put("adminReply", m.adminReply)
                    put("timestamp", m.timestamp)
                }
            )
        }
        prefs.edit().putString("feedback_json", arr.toString()).commit()
    }

    fun loadReminders(): List<BroadcastReminder> {
        val raw = prefs.getString("reminders_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                BroadcastReminder(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    content = o.optString("content"),
                    targetUsername = o.optString("targetUsername", "ALL"),
                    timestamp = o.optString("timestamp")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveReminders(list: List<BroadcastReminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("content", r.content)
                    put("targetUsername", r.targetUsername)
                    put("timestamp", r.timestamp)
                }
            )
        }
        prefs.edit().putString("reminders_json", arr.toString()).commit()
    }

    fun buildFullBackupJson(): String {
        return JSONObject().apply {
            put("app", "Hesabat-Admin-Panel")
            put("adminEmail", ADMIN_MASTER_EMAIL)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("accounts", JSONArray(prefs.getString("accounts_json", "[]") ?: "[]"))
            put("reminders", JSONArray(prefs.getString("reminders_json", "[]") ?: "[]"))
            put("feedback", JSONArray(prefs.getString("feedback_json", "[]") ?: "[]"))
        }.toString(2)
    }

    fun importFullBackupJson(jsonText: String): Result<Int> {
        return runCatching {
            val root = JSONObject(jsonText)
            val accArr = root.optJSONArray("accounts") ?: JSONArray(jsonText)
            prefs.edit().putString("accounts_json", accArr.toString()).commit()
            runCatching { vaultFile.writeText(accArr.toString(), Charsets.UTF_8) }
            root.optJSONArray("reminders")?.let {
                prefs.edit().putString("reminders_json", it.toString()).commit()
            }
            root.optJSONArray("feedback")?.let {
                prefs.edit().putString("feedback_json", it.toString()).commit()
            }
            accArr.length()
        }
    }

    fun clearAllVaultData() {
        prefs.edit()
            .remove("accounts_json")
            .remove("reminders_json")
            .remove("feedback_json")
            .commit()
        runCatching { if (vaultFile.exists()) vaultFile.delete() }
    }

    suspend fun syncWithCloudEndpoint(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = getCloudSyncUrl().trim()
            if (endpoint.isEmpty()) {
                return@runCatching "يەرلىك ئامباردا مۇۋەپپەقىيەتلىك ساقلاندى (بۇلۇت ئادرېسى تەڭشەلمىگەن)"
            }
            val targetUrl = if (endpoint.endsWith(".json")) endpoint else "$endpoint/hesabat_sync.json"
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { out ->
                out.write(buildFullBackupJson().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code in 200..299) {
                "بۇلۇت سۇپىسى بىلەن مۇۋەپپەقىيەتلىك ماس قەدەملەندى!"
            } else {
                "يەرلىكتە ساقلاندى (بۇلۇت جاۋاب كودى: $code)"
            }
        }
    }

    private fun createInitialSampleAccounts(): List<ClientAccountRecord> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return listOf(
            ClientAccountRecord(
                id = UUID.randomUUID().toString(),
                fullName = "ئالىمجان مەمەت",
                username = "alim01",
                password = "123456",
                email = "alim01@gmail.com",
                streetName = "ئىتتىپاق كوچىسى",
                houseNumber = "12-ئۆي",
                flowAmount = "100 GB",
                openDate = today,
                notes = "پالانى ئىسىملىك ھېسابات تىزىملاپ كىرمەكچى (تەستىق كۈتۈۋاتىدۇ)",
                status = "PENDING",
                licenseCode = generateVerificationCode("alim01", false),
                isVip = false,
                vipExpireEpochMillis = 0L,
                paidMonths = List(12) { it < 3 }
            ),
            ClientAccountRecord(
                id = UUID.randomUUID().toString(),
                fullName = "پەرىدە خانىم",
                username = "parida",
                password = "654321",
                email = "parida@gmail.com",
                streetName = "بوستان كوچىسى",
                houseNumber = "8-ئۆي",
                flowAmount = "200 GB",
                openDate = today,
                notes = "VIP خېرىدار — كودسىز بىۋاسىتە كىرەلەيدۇ",
                status = "VIP",
                licenseCode = generateVerificationCode("parida", true),
                isVip = true,
                vipExpireEpochMillis = System.currentTimeMillis() + 30L * 24L * 3600L * 1000L,
                paidMonths = List(12) { true }
            )
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

enum class AdminNavTab(val label: String, val icon: ImageVector) {
    HOME("باش بەت", Icons.Filled.Dashboard),
    APPROVALS("تەستىق ۋە كود", Icons.Filled.VerifiedUser),
    ACCOUNTS("ھېساباتلار", Icons.Filled.Group),
    VIP("VIP مۇددىتى", Icons.Filled.WorkspacePremium),
    VAULT("ئەسلەتمە/ئامبار", Icons.Filled.Storage)
}

@Composable
fun AdminRootApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val vault = remember { AdminVaultStorage(context.applicationContext) }

    var isAuthenticated by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(AdminNavTab.HOME) }

    var accounts by remember { mutableStateOf(vault.loadAccounts()) }
    var reminders by remember { mutableStateOf(vault.loadReminders()) }
    var feedbacks by remember { mutableStateOf(vault.loadFeedbackMessages()) }

    // ئىزدەش بېتى ھالىتى
    var isSearchPageOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // يېڭى ھېسابات قوشۇش / تەھرىرلەش دىئالوگى
    var showAccountEditor by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<ClientAccountRecord?>(null) }

    // ھەقىقىي ۋاقىتلىق تور تېزلىكى ئۆلچىگۈچ
    var liveSpeedText by remember { mutableStateOf("0.0 KB/s") }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000L)
            val nowRx = TrafficStats.getTotalRxBytes()
            val nowTx = TrafficStats.getTotalTxBytes()
            val delta = ((nowRx - lastRx) + (nowTx - lastTx)).coerceAtLeast(0L)
            lastRx = nowRx
            lastTx = nowTx
            liveSpeedText = if (delta >= 1024 * 1024) {
                String.format(Locale.US, "%.2f MB/s", delta / (1024f * 1024f))
            } else {
                String.format(Locale.US, "%.1f KB/s", delta / 1024f)
            }
        }
    }

    val saveAndUpdateAccounts: (List<ClientAccountRecord>) -> Unit = { updated ->
        accounts = updated
        vault.saveAccounts(updated)
    }

    val sendEmailToClient: (String, String, String) -> Unit = { targetEmail, subject, body ->
        runCatching {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${targetEmail.ifBlank { ADMIN_MASTER_EMAIL }}")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(Intent.createChooser(intent, "ئېلخەت ئارقىلىق ئەۋەتىش"))
        }
    }

    if (!isAuthenticated) {
        AdminLoginPortal(
            onVerifyPassword = { pwd ->
                if (vault.verifyAdminPassword(pwd)) {
                    isAuthenticated = true
                    true
                } else {
                    false
                }
            }
        )
    } else {
        if (isSearchPageOpen) {
            BackHandler { isSearchPageOpen = false }
            DedicatedAdminSearchPage(
                allAccounts = accounts,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onBack = { isSearchPageOpen = false },
                onCopyText = { label, text ->
                    clipboard.setText(AnnotatedString(text))
                    scope.launch { snackbarHostState.showSnackbar("$label كۆچۈرۈلدى: $text") }
                },
                onToggleMonth = { acc, monthIdx ->
                    val updatedMonths = acc.paidMonths.toMutableList().also {
                        it[monthIdx] = !it[monthIdx]
                    }
                    saveAndUpdateAccounts(
                        accounts.map { if (it.id == acc.id) it.copy(paidMonths = updatedMonths) else it }
                    )
                },
                onEditAccount = { acc ->
                    editingAccount = acc
                    showAccountEditor = true
                },
                onDeleteAccount = { acc ->
                    saveAndUpdateAccounts(accounts.filterNot { it.id == acc.id })
                    scope.launch { snackbarHostState.showSnackbar("«${acc.fullName}» ھېساباتى ئۆچۈرۈلدى") }
                }
            )
        } else {
            Scaffold(
                containerColor = AdminDarkBg,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        containerColor = Color(0xFF0D1528),
                        tonalElevation = 8.dp
                    ) {
                        AdminNavTab.entries.forEach { tab ->
                            val pendingCount = accounts.count { it.status == "PENDING" }
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (tab == AdminNavTab.APPROVALS && pendingCount > 0) {
                                                Badge(containerColor = AdminRose) {
                                                    Text("$pendingCount", color = Color.White)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (currentTab == tab) FontWeight.ExtraBold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                },
                floatingActionButton = {
                    if (currentTab == AdminNavTab.ACCOUNTS || currentTab == AdminNavTab.HOME) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                editingAccount = null
                                showAccountEditor = true
                            },
                            containerColor = AdminIndigo,
                            contentColor = Color.White,
                            icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                            text = { Text("يېڭى ھېسابات قوشۇش", fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                ) {
                    // ئۈستى قىسىم: باشقۇرغۇچى نامى، ئېلخەت ۋە تور ئېقىم ئۆلچىگۈچ
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 10.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = AdminCardSurface,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Brush.linearGradient(listOf(AdminIndigo, AdminTurquoise))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White)
                                }
                                Column {
                                    Text(
                                        text = "باش باشقۇرغۇچى مەركىزى",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = ADMIN_MASTER_EMAIL,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AdminTurquoise
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = AdminEmerald.copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, AdminEmerald.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.Speed, contentDescription = null, tint = AdminEmerald, modifier = Modifier.size(14.dp))
                                        Text(liveSpeedText, style = MaterialTheme.typography.labelSmall, color = AdminEmerald, fontWeight = FontWeight.Bold)
                                    }
                                }
                                IconButton(onClick = { isAuthenticated = false }) {
                                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "چېكىنىش", tint = AdminRose)
                                }
                            }
                        }
                    }

                    // 5 بەتنىڭ مەزمۇنى
                    when (currentTab) {
                        AdminNavTab.HOME -> {
                            AdminHomeTabContent(
                                accounts = accounts,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                onOpenSearchPage = { queryText ->
                                    searchQuery = queryText
                                    isSearchPageOpen = true
                                },
                                onQuickNavigate = { targetTab -> currentTab = targetTab }
                            )
                        }

                        AdminNavTab.APPROVALS -> {
                            AdminApprovalsAndCodesTab(
                                accounts = accounts,
                                vault = vault,
                                onApproveAndGenerateCode = { acc, isVip ->
                                    val newCode = vault.generateVerificationCode(acc.username, isVip)
                                    val newStatus = if (isVip) "VIP" else "APPROVED"
                                    val expireMillis = if (isVip) System.currentTimeMillis() + 30L * 24L * 3600L * 1000L else 0L
                                    saveAndUpdateAccounts(
                                        accounts.map {
                                            if (it.id == acc.id) {
                                                it.copy(
                                                    status = newStatus,
                                                    licenseCode = newCode,
                                                    isVip = isVip,
                                                    vipExpireEpochMillis = expireMillis
                                                )
                                            } else it
                                        }
                                    )
                                    clipboard.setText(AnnotatedString(newCode))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("«${acc.username}» تەستىقلىنىپ كودى كۆچۈرۈلدى: $newCode")
                                    }
                                },
                                onPreCreateUserWithCode = { fullName, username, password, street, house, isVip ->
                                    val code = vault.generateVerificationCode(username, isVip)
                                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    val newAcc = ClientAccountRecord(
                                        id = UUID.randomUUID().toString(),
                                        fullName = fullName.ifBlank { username },
                                        username = username.trim(),
                                        password = password.ifBlank { "123456" },
                                        email = if (username.contains("@")) username else "$username@gmail.com",
                                        streetName = street,
                                        houseNumber = house,
                                        flowAmount = "100 GB",
                                        openDate = today,
                                        notes = "باشقۇرغۇچى تەرىپىدىن ئېچىپ بېرىلگەن ھېسابات",
                                        status = if (isVip) "VIP" else "APPROVED",
                                        licenseCode = code,
                                        isVip = isVip,
                                        vipExpireEpochMillis = if (isVip) System.currentTimeMillis() + 30L * 24L * 3600L * 1000L else 0L,
                                        paidMonths = List(12) { false }
                                    )
                                    saveAndUpdateAccounts(listOf(newAcc) + accounts)
                                    clipboard.setText(AnnotatedString(code))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("ھېسابات قۇرۇلدى ۋە كودى كۆچۈرۈلدى: $code")
                                    }
                                },
                                onSendCodeByEmail = { acc ->
                                    val code = acc.licenseCode.ifBlank { vault.generateVerificationCode(acc.username, acc.isVip) }
                                    sendEmailToClient(
                                        acc.email,
                                        "Hesabat ئەپىگە كىرىش دەلىللەش كودىڭىز",
                                        "ھۆرمەتلىك ${acc.fullName}،\n\nسىزنىڭ ھېسابات ئىسمىڭىز: ${acc.username}\nدەلىللەش كودىڭىز: $code\n\nمۇشۇ كودنى كىرگۈزۈپ ئەپكە كىرەلەيسىز."
                                    )
                                },
                                onCopyCode = { code ->
                                    clipboard.setText(AnnotatedString(code))
                                    scope.launch { snackbarHostState.showSnackbar("دەلىللەش كودى كۆچۈرۈلدى: $code") }
                                }
                            )
                        }

                        AdminNavTab.ACCOUNTS -> {
                            AdminAllAccountsTab(
                                accounts = accounts,
                                onSearchClick = {
                                    searchQuery = ""
                                    isSearchPageOpen = true
                                },
                                onToggleMonth = { acc, mIdx ->
                                    val updatedMonths = acc.paidMonths.toMutableList().also {
                                        it[mIdx] = !it[mIdx]
                                    }
                                    saveAndUpdateAccounts(
                                        accounts.map { if (it.id == acc.id) it.copy(paidMonths = updatedMonths) else it }
                                    )
                                },
                                onEdit = { acc ->
                                    editingAccount = acc
                                    showAccountEditor = true
                                },
                                onDelete = { acc ->
                                    saveAndUpdateAccounts(accounts.filterNot { it.id == acc.id })
                                    scope.launch { snackbarHostState.showSnackbar("«${acc.fullName}» ئۆچۈرۈلدى") }
                                },
                                onCopy = { label, value ->
                                    clipboard.setText(AnnotatedString(value))
                                    scope.launch { snackbarHostState.showSnackbar("$label كۆچۈرۈلدى") }
                                }
                            )
                        }

                        AdminNavTab.VIP -> {
                            AdminVipManagementTab(
                                accounts = accounts,
                                vault = vault,
                                onSetVipDuration = { acc, days ->
                                    val expireTime = if (days <= 0) 0L else System.currentTimeMillis() + days.toLong() * 24L * 3600L * 1000L
                                    val vipCode = vault.generateVerificationCode(acc.username, days > 0)
                                    saveAndUpdateAccounts(
                                        accounts.map {
                                            if (it.id == acc.id) {
                                                it.copy(
                                                    isVip = days > 0,
                                                    status = if (days > 0) "VIP" else "APPROVED",
                                                    vipExpireEpochMillis = expireTime,
                                                    licenseCode = vipCode
                                                )
                                            } else it
                                        }
                                    )
                                    scope.launch {
                                        val msg = if (days > 0) "«${acc.fullName}» غا $days كۈنلۈك VIP بېكىتىلدى (كودسىز كىرەلەيدۇ)" else "VIP بىكار قىلىندى"
                                        snackbarHostState.showSnackbar(msg)
                                    }
                                }
                            )
                        }

                        AdminNavTab.VAULT -> {
                            AdminVaultAndCommunicationTab(
                                vault = vault,
                                reminders = reminders,
                                feedbacks = feedbacks,
                                onSendReminder = { title, content, target ->
                                    val newRem = BroadcastReminder(
                                        id = UUID.randomUUID().toString(),
                                        title = title,
                                        content = content,
                                        targetUsername = target.ifBlank { "ALL" },
                                        timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                                    )
                                    val updated = listOf(newRem) + reminders
                                    reminders = updated
                                    vault.saveReminders(updated)
                                    scope.launch { snackbarHostState.showSnackbar("ئەسلەتمە يوللاندى!") }
                                },
                                onReplyFeedback = { msgItem, replyText ->
                                    val updated = feedbacks.map {
                                        if (it.id == msgItem.id) it.copy(adminReply = replyText) else it
                                    }
                                    feedbacks = updated
                                    vault.saveFeedbackMessages(updated)
                                    sendEmailToClient(
                                        "${msgItem.clientUsername}@gmail.com",
                                        "Hesabat باشقۇرغۇچىدىن جاۋاب",
                                        replyText
                                    )
                                    scope.launch { snackbarHostState.showSnackbar("جاۋاب ساقلاندى ۋە يوللاندى!") }
                                },
                                onClearAllVault = {
                                    vault.clearAllVaultData()
                                    accounts = emptyList()
                                    reminders = emptyList()
                                    feedbacks = emptyList()
                                    scope.launch { snackbarHostState.showSnackbar("ئەپ ئىچىدىكى بارلىق مەلۇماتلار پاكىز تازىلاندى!") }
                                },
                                onReloadAccounts = {
                                    accounts = vault.loadAccounts()
                                    reminders = vault.loadReminders()
                                    feedbacks = vault.loadFeedbackMessages()
                                },
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAccountEditor) {
        AccountEditorDialog(
            initial = editingAccount,
            vault = vault,
            onDismiss = {
                showAccountEditor = false
                editingAccount = null
            },
            onSave = { record ->
                val exists = accounts.any { it.id == record.id }
                val updated = if (exists) {
                    accounts.map { if (it.id == record.id) record else it }
                } else {
                    listOf(record) + accounts
                }
                saveAndUpdateAccounts(updated)
                showAccountEditor = false
                editingAccount = null
                scope.launch { snackbarHostState.showSnackbar("ھېسابات مەلۇماتى ساقلاندى!") }
            }
        )
    }
}

@Composable
fun AdminLoginPortal(onVerifyPassword: (String) -> Boolean) {
    var password by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AdminDarkBg, Color(0xFF101D3B)))),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .padding(20.dp),
            shape = RoundedCornerShape(28.dp),
            color = AdminCardSurface,
            border = BorderStroke(1.5.dp, AdminTurquoise.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(AdminIndigo, AdminTurquoise))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }

                Text(
                    text = "Hesabat باشقۇرغۇچى مەركىزى",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "بارلىق ھېساباتلارنى تەستىقلاش، دەلىللەش كودى ئەۋەتىش ۋە VIP مۇددىتى بەلگىلەش سۇپىسى",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMsg = null
                    },
                    label = { Text("باشقۇرغۇچى مەخپىي نومۇرى (162142)") },
                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, tint = AdminTurquoise) },
                    trailingIcon = {
                        IconButton(onClick = { showPwd = !showPwd }) {
                            Icon(if (showPwd) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                        }
                    },
                    visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg != null) {
                    Text(errorMsg.orEmpty(), color = AdminRose, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (!onVerifyPassword(password)) {
                            errorMsg = "مەخپىي نومۇر خاتا! (دەسلەپكى نومۇر: 162142)"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("باشقۇرغۇچى سۈپىتىدە كىرىش", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AdminHomeTabContent(
    accounts: List<ClientAccountRecord>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearchPage: (String) -> Unit,
    onQuickNavigate: (AdminNavTab) -> Unit
) {
    val totalCount = accounts.size
    val pendingCount = accounts.count { it.status == "PENDING" }
    val paidCount = accounts.count { it.paidCount() == 12 }
    val debtCount = accounts.count { it.paidCount() < 12 }
    val vipCount = accounts.count { it.isVipActive() }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        // ئىزدەش رامكىسى (ئىسمى، كوچىسى، ئادرېسى ياكى ھېسابات ئىسمى بويىچە يېڭى بەتتە ئىزدەيدۇ)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("ئىسىم، كوچا، ئادرېس ياكى ھېسابات ئىسمىنى يېزىڭ...") },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = AdminTurquoise)
                        },
                        trailingIcon = {
                            Button(
                                onClick = { onOpenSearchPage(searchQuery) },
                                colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text("ئىزدەش", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // سىنبەلگىلىك باش تاختا كارتىلىرى (چەكسە ئىچىدىكى مەزمۇنى ئېچىلىدۇ)
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardIconTile(
                    title = "جەمئىي ھېساباتلار",
                    count = "$totalCount",
                    icon = Icons.Filled.Group,
                    accent = AdminIndigo,
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickNavigate(AdminNavTab.ACCOUNTS) }
                )
                DashboardIconTile(
                    title = "ھەق تاپشۇرغانلار",
                    count = "$paidCount",
                    icon = Icons.Filled.CheckCircle,
                    accent = AdminEmerald,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSearchPage("PAID") }
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardIconTile(
                    title = "قەرزگە ئاچقۇزغانلار",
                    count = "$debtCount",
                    icon = Icons.Filled.WarningAmber,
                    accent = AdminRose,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSearchPage("UNPAID") }
                )
                DashboardIconTile(
                    title = "VIP ھېساباتلار",
                    count = "$vipCount",
                    icon = Icons.Filled.WorkspacePremium,
                    accent = AdminAmber,
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickNavigate(AdminNavTab.VIP) }
                )
            }
        }

        if (pendingCount > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = AdminRose.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, AdminRose),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onQuickNavigate(AdminNavTab.APPROVALS) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = AdminRose, modifier = Modifier.size(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "يېڭى تىزىملىتىش ئۇچۇرى بار! ($pendingCount دانە)",
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = "خېرىدارلار ئەپكە كىرمەكچى — چېكىپ دەلىللەش كودى ئەۋەتىپ بېرىڭ",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = AdminRose)
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardIconTile(
    title: String,
    count: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        color = AdminCardSurface,
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(26.dp))
            }
            Text(count, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = accent)
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun AdminApprovalsAndCodesTab(
    accounts: List<ClientAccountRecord>,
    vault: AdminVaultStorage,
    onApproveAndGenerateCode: (ClientAccountRecord, Boolean) -> Unit,
    onPreCreateUserWithCode: (String, String, String, String, String, Boolean) -> Unit,
    onSendCodeByEmail: (ClientAccountRecord) -> Unit,
    onCopyCode: (String) -> Unit
) {
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var house by remember { mutableStateOf("") }
    var isVipNew by remember { mutableStateOf(false) }

    val previewCode = remember(username, isVipNew) {
        vault.generateVerificationCode(username, isVipNew)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        // 1. خېرىدارغا ئىشلەتكۈچى ئىسمى ۋە دەلىللەش كودى ياساپ بېرىش رامكىسى
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.2.dp, AdminTurquoise.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = AdminTurquoise)
                        Text(
                            text = "خېرىدارغا ھېسابات ئىسمى ۋە دەلىللەش كودى بېرىش",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = "خېرىدارغا ئىشلەتكۈچى ئىسمى بېكىتىپ بەرسىڭىز ياكى خېرىدار ئۆزى تىزىملاتقان ئىسىمنى يازسىڭىز، شۇ كىشىنىڭ ئەپكە كىرىش دەلىللەش كودى چىقىدۇ:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("ھېسابات ئىسمى ياكى ئېلخەت (مەسىلەن: alim01) *") },
                        leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null, tint = AdminTurquoise) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("خېرىدارنىڭ تولۇق ئىسمى") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("مەخپىي نومۇرى") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = street,
                            onValueChange = { street = it },
                            label = { Text("كوچا ئىسمى / ئادرېس") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = house,
                            onValueChange = { house = it },
                            label = { Text("ئۆي نومۇرى") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("VIP ھوقۇقى بېرىش (كودسىز كىرەلەيدۇ):", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = isVipNew, onCheckedChange = { isVipNew = it })
                    }

                    if (previewCode.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AdminEmerald.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, AdminEmerald),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("مەخسۇس دەلىللەش كودى:", style = MaterialTheme.typography.labelSmall)
                                    Text(previewCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = AdminEmerald)
                                }
                                Button(
                                    onClick = { onCopyCode(previewCode) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AdminEmerald)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("كۆچۈرۈش", color = Color.White)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (username.isNotBlank()) {
                                onPreCreateUserWithCode(fullName, username, password, street, house, isVipNew)
                                fullName = ""
                                username = ""
                                password = ""
                                street = ""
                                house = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AddTask, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ھېساباتنى تىزىملاپ كودنى ساقلاش", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. تىزىملاپ كىرمەكچى بولغان ھېساباتلار تىزىملىكى
        item {
            Text(
                text = "تىزىملاپ كىرمەكچى بولغانلار ۋە دەلىللەش كودلىرى (${accounts.size}):",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold
            )
        }

        items(accounts, key = { it.id }) { acc ->
            val code = acc.licenseCode.ifBlank { vault.generateVerificationCode(acc.username, acc.isVip) }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AdminCardSurface,
                border = BorderStroke(
                    1.2.dp,
                    if (acc.status == "PENDING") AdminRose else if (acc.isVip) AdminAmber else AdminTurquoise.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (acc.status == "PENDING") "🔔 «${acc.fullName}» ھېسابات تىزىملاپ كىرمەكچى!" else acc.fullName,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (acc.status == "PENDING") AdminRose else Color.White
                            )
                            Text(
                                text = "ھېسابات ئىسمى: ${acc.username} | مەخپىي نومۇرى: ${acc.password}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AdminTurquoise
                            )
                            if (acc.streetName.isNotBlank() || acc.houseNumber.isNotBlank()) {
                                Text(
                                    text = "ئادرېسى: ${acc.streetName} ${acc.houseNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (acc.status == "PENDING") AdminRose else if (acc.isVip) AdminAmber else AdminEmerald
                        ) {
                            Text(
                                text = if (acc.status == "PENDING") "كۈتۈۋاتىدۇ" else if (acc.isVip) "VIP" else "تەستىقلانغان",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("دەلىللەش كودى: $code", fontWeight = FontWeight.ExtraBold, color = AdminEmerald)
                            Row {
                                IconButton(onClick = { onCopyCode(code) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "كۆچۈرۈش", tint = AdminTurquoise, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onSendCodeByEmail(acc) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Email, contentDescription = "ئېلخەت ئەۋەتىش", tint = AdminAmber, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onApproveAndGenerateCode(acc, true) }) {
                            Text("VIP ئېچىپ بېرىش", color = AdminAmber, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { onApproveAndGenerateCode(acc, false) },
                            colors = ButtonDefaults.buttonColors(containerColor = AdminEmerald),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تەستىقلاپ كود ئەۋەتىش", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminAllAccountsTab(
    accounts: List<ClientAccountRecord>,
    onSearchClick: () -> Unit,
    onToggleMonth: (ClientAccountRecord, Int) -> Unit,
    onEdit: (ClientAccountRecord) -> Unit,
    onDelete: (ClientAccountRecord) -> Unit,
    onCopy: (String, String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "بارلىق خېرىدار ھېساباتلىرى (${accounts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                OutlinedButton(
                    onClick = onSearchClick,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AdminTurquoise)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = AdminTurquoise, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تەپسىلىي ئىزدەش", color = AdminTurquoise)
                }
            }
        }

        items(accounts, key = { it.id }) { acc ->
            AdminClientDetailCard(
                acc = acc,
                onToggleMonth = { mIdx -> onToggleMonth(acc, mIdx) },
                onEdit = { onEdit(acc) },
                onDelete = { onDelete(acc) },
                onCopy = onCopy
            )
        }
    }
}

@Composable
fun AdminClientDetailCard(
    acc: ClientAccountRecord,
    onToggleMonth: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AdminCardSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AdminIndigo, AdminTurquoise))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White)
                    }
                    Column {
                        Text(acc.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = "تىزىملاتقان ۋاقتى: ${acc.openDate.ifBlank { "بەلگىلەنمىگەن" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray
                        )
                    }
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "تەھرىرلەش", tint = AdminTurquoise)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "ئۆچۈرۈش", tint = AdminRose)
                    }
                }
            }

            // ھېسابات ئىسمى، مەخپىي نومۇرى، كوچا ۋە ئۆي نومۇرى
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.05f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ھېسابات ئىسمى: ${acc.username}", fontWeight = FontWeight.Bold, color = AdminTurquoise)
                        IconButton(onClick = { onCopy("ھېسابات ئىسمى", acc.username) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = AdminTurquoise, modifier = Modifier.size(16.dp))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "مەخپىي نومۇرى: ${if (showPassword) acc.password else "••••••••"}",
                            fontWeight = FontWeight.Bold,
                            color = AdminAmber
                        )
                        Row {
                            IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.size(28.dp)) {
                                Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onCopy("مەخپىي نومۇر", acc.password) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = AdminAmber, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Text("ئادرېسى: ${acc.streetName} ${acc.houseNumber} | ئېقىمى: ${acc.flowAmount}", style = MaterialTheme.typography.bodySmall)
                    Text("دەلىللەش كودى: ${acc.licenseCode}", style = MaterialTheme.typography.labelSmall, color = AdminEmerald)
                }
            }

            // 12 ئايلىق پۇل تۆلەش ئەھۋالى
            Text(
                text = "پۇل تۆلىگەن ئايلار (${acc.paidCount()} / 12):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )

            val rows = (0 until 12).chunked(4)
            rows.forEach { rIndices ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rIndices.forEach { mIdx ->
                        val paid = acc.paidMonths.getOrElse(mIdx) { false }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (paid) AdminEmerald.copy(alpha = 0.22f) else AdminRose.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, if (paid) AdminEmerald else AdminRose.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggleMonth(mIdx) }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("${mIdx + 1}-ئاي", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (paid) "تۆلەندى" else "تۆلەنمىدى",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (paid) AdminEmerald else AdminRose
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminVipManagementTab(
    accounts: List<ClientAccountRecord>,
    vault: AdminVaultStorage,
    onSetVipDuration: (ClientAccountRecord, Int) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.2.dp, AdminAmber.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = AdminAmber)
                        Text("VIP ھوقۇقى ۋە مۇددەت بەلگىلەش مەركىزى", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        text = "VIP ئېچىپ بېرىلگەن ھېساباتلار دەلىللەش كودى ئىشلەتمەي تۇرۇپ بىۋاسىتە ئەپكە كىرەلەيدۇ. ھەر بىر كىشىگە VIP مۇددىتى بەلگىلىيەلەيسىز:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
            }
        }

        items(accounts, key = { it.id }) { acc ->
            val activeVip = acc.isVipActive()
            val remDays = acc.vipRemainingDays()

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.dp, if (activeVip) AdminAmber else Color.White.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(acc.fullName, fontWeight = FontWeight.ExtraBold)
                            Text("ھېسابات: ${acc.username}", style = MaterialTheme.typography.labelSmall, color = AdminTurquoise)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (activeVip) AdminAmber.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = if (activeVip) "VIP ($remDays كۈن قالدى)" else "نورمال ھېسابات",
                                color = if (activeVip) AdminAmber else Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text("مۇددەت تاللاپ VIP ئېچىش:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            7 to "7 كۈن",
                            30 to "1 ئاي",
                            90 to "3 ئاي",
                            180 to "6 ئاي",
                            365 to "1 يىل"
                        ).forEach { (days, label) ->
                            FilterChip(
                                selected = false,
                                onClick = { onSetVipDuration(acc, days) },
                                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, tint = AdminAmber, modifier = Modifier.size(15.dp)) },
                                label = { Text(label) }
                            )
                        }

                        if (activeVip) {
                            FilterChip(
                                selected = false,
                                onClick = { onSetVipDuration(acc, 0) },
                                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = AdminRose, modifier = Modifier.size(15.dp)) },
                                label = { Text("VIP توختىتىش", color = AdminRose) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminVaultAndCommunicationTab(
    vault: AdminVaultStorage,
    reminders: List<BroadcastReminder>,
    feedbacks: List<ClientFeedbackMessage>,
    onSendReminder: (String, String, String) -> Unit,
    onReplyFeedback: (ClientFeedbackMessage, String) -> Unit,
    onClearAllVault: () -> Unit,
    onReloadAccounts: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var remTitle by remember { mutableStateOf("") }
    var remContent by remember { mutableStateOf("") }
    var remTarget by remember { mutableStateOf("ALL") }

    var cloudUrl by remember { mutableStateOf(vault.getCloudSyncUrl()) }
    var newAdminPwd by remember { mutableStateOf("") }
    var confirmClearDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(vault.buildFullBackupJson().toByteArray(Charsets.UTF_8))
                }
                onShowMessage("بارلىق مەلۇماتلار JSON ھۆججىتىگە چىقىرىلدى!")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                vault.importFullBackupJson(text).onSuccess { count ->
                    onReloadAccounts()
                    onShowMessage("$count دانە ھېسابات مەلۇماتى ئەسلىگە كەلتۈرۈلدى!")
                }
            }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        // 1. خېرىدارلارغا ئەسلەتمە يوللاش
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.dp, AdminIndigo.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Campaign, contentDescription = null, tint = AdminTurquoise)
                        Text("خېرىدارلارغا ئەسلەتمە يوللاش", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    }
                    OutlinedTextField(
                        value = remTitle,
                        onValueChange = { remTitle = it },
                        label = { Text("ئەسلەتمە ماۋزۇسى") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remContent,
                        onValueChange = { remContent = it },
                        label = { Text("ئەسلەتمە مەزمۇنى...") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remTarget,
                        onValueChange = { remTarget = it },
                        label = { Text("قوبۇل قىلغۇچى (ALL = ھەممەيلەن ياكى ھېسابات ئىسمى)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (remContent.isNotBlank()) {
                                onSendReminder(remTitle.ifBlank { "باشقۇرغۇچى ئەسلەتمىسى" }, remContent, remTarget)
                                remTitle = ""
                                remContent = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AdminEmerald),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ئەسلەتمە تارقىتىش", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. خېرىدارلارنىڭ ئىنكاسلىرى ۋە جاۋاب يېزىش
        item {
            Text("خېرىدارلاردىن كەلگەن ئىنكاسلار (${feedbacks.size}):", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
        }

        items(feedbacks, key = { it.id }) { item ->
            var replyInput by remember(item.id) { mutableStateOf(item.adminReply) }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.dp, AdminTurquoise.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${item.clientName} (${item.clientUsername})", fontWeight = FontWeight.ExtraBold, color = AdminTurquoise)
                    Text(item.message, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = replyInput,
                        onValueChange = { replyInput = it },
                        label = { Text("باشقۇرغۇچى جاۋابى يېزىش...") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onReplyFeedback(item, replyInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("جاۋاب قايتۇرۇش", color = Color.White)
                    }
                }
            }
        }

        // 3. مەخسۇس ساقلاش سۇپىسى، بۇلۇت ئۇلىنىشى ۋە ئەپنى تازىلاش
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = AdminCardSurface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, tint = AdminEmerald)
                        Text("مەخسۇس ساقلاش سۇپىسى ۋە تازىلاش", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    }

                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = {
                            cloudUrl = it
                            vault.setCloudSyncUrl(it)
                        },
                        label = { Text("بۇلۇت ماس قەدەملەش ئادرېسى (Firebase / REST URL)") },
                        placeholder = { Text("https://your-project.firebaseio.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                val res = vault.syncWithCloudEndpoint()
                                onShowMessage(res.getOrElse { "يەرلىك ئامباردا ساقلاندى" })
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AdminTurquoise),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("بۇلۇت ۋە خېرىدار ئەپى بىلەن ماس قەدەملەش", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("hesabat_admin_backup.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("زاپاس چىقىرىش")
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("زاپاس ئەكىرىش")
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                    OutlinedTextField(
                        value = newAdminPwd,
                        onValueChange = { newAdminPwd = it },
                        label = { Text("باشقۇرغۇچى مەخپىي نومۇرىنى يېڭىلاش") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (vault.updateAdminPassword(newAdminPwd)) {
                                newAdminPwd = ""
                                onShowMessage("باشقۇرغۇچى مەخپىي نومۇرى يېڭىلاندى!")
                            } else {
                                onShowMessage("كەم دېگەندە 4 خانىلىق مەخپىي نومۇر يېزىڭ")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("مەخپىي نومۇرنى ساقلاش", color = Color.White)
                    }

                    Button(
                        onClick = { confirmClearDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AdminRose),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ئەپ ئىچىدىكى بارلىق مەلۇماتلارنى تازىلاش", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (confirmClearDialog) {
        AlertDialog(
            onDismissRequest = { confirmClearDialog = false },
            title = { Text("ئەپ ئىچىنى تازىلاش", fontWeight = FontWeight.Bold) },
            text = { Text("بارلىق ساقلانغان ھېساباتلار ۋە ئەسلەتمىلەرنى پۈتۈنلەي ئۆچۈرۈپ تازىلامسىز؟") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearDialog = false
                        onClearAllVault()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AdminRose)
                ) {
                    Text("ھەئە، تازىلاش", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDialog = false }) {
                    Text("بىكار قىلىش")
                }
            }
        )
    }
}

@Composable
fun DedicatedAdminSearchPage(
    allAccounts: List<ClientAccountRecord>,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onToggleMonth: (ClientAccountRecord, Int) -> Unit,
    onEditAccount: (ClientAccountRecord) -> Unit,
    onDeleteAccount: (ClientAccountRecord) -> Unit
) {
    val cleanQuery = query.trim().lowercase()
    val filtered = remember(allAccounts, cleanQuery) {
        when {
            cleanQuery.isEmpty() -> allAccounts
            cleanQuery == "paid" -> allAccounts.filter { it.paidCount() == 12 }
            cleanQuery == "unpaid" -> allAccounts.filter { it.paidCount() < 12 }
            else -> allAccounts.filter { acc ->
                acc.fullName.lowercase().contains(cleanQuery) ||
                    acc.username.lowercase().contains(cleanQuery) ||
                    acc.streetName.lowercase().contains(cleanQuery) ||
                    acc.houseNumber.lowercase().contains(cleanQuery) ||
                    acc.email.lowercase().contains(cleanQuery) ||
                    acc.licenseCode.lowercase().contains(cleanQuery) ||
                    acc.notes.lowercase().contains(cleanQuery)
            }
        }
    }

    Scaffold(containerColor = AdminDarkBg) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = AdminCardSurface,
                    border = BorderStroke(1.dp, AdminTurquoise.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("قايتىش", color = Color.White)
                            }
                            Text(
                                text = "ئىزدەش نەتىجىسى (${filtered.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            placeholder = { Text("ئىسىم، كوچا، ئادرېس ياكى ھېسابات ئىسمىنى يېزىڭ...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AdminTurquoise) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { onQueryChange("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AdminCardSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.SearchOff, contentDescription = null, tint = AdminRose, modifier = Modifier.size(46.dp))
                            Text("بۇ ئىزدەش شەرتىگە ماس كېلىدىغان ھېسابات تېپىلمىدى", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { acc ->
                    AdminClientDetailCard(
                        acc = acc,
                        onToggleMonth = { mIdx -> onToggleMonth(acc, mIdx) },
                        onEdit = { onEditAccount(acc) },
                        onDelete = { onDeleteAccount(acc) },
                        onCopy = onCopyText
                    )
                }
            }
        }
    }
}

@Composable
fun AccountEditorDialog(
    initial: ClientAccountRecord?,
    vault: AdminVaultStorage,
    onDismiss: () -> Unit,
    onSave: (ClientAccountRecord) -> Unit
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var fullName by remember { mutableStateOf(initial?.fullName.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var streetName by remember { mutableStateOf(initial?.streetName.orEmpty()) }
    var houseNumber by remember { mutableStateOf(initial?.houseNumber.orEmpty()) }
    var flowAmount by remember { mutableStateOf(initial?.flowAmount ?: "100 GB") }
    var openDate by remember { mutableStateOf(initial?.openDate ?: today) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var isVip by remember { mutableStateOf(initial?.isVip ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "يېڭى خېرىدار ھېساباتى قوشۇش" else "ھېسابات تەھرىرلەش", fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("خېرىدارنىڭ ئىسمى *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("ھېسابات ئىسمى (Username) *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("مەخپىي نومۇرى *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = streetName,
                    onValueChange = { streetName = it },
                    label = { Text("كوچا ئىسمى / ئادرېس") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = houseNumber,
                    onValueChange = { houseNumber = it },
                    label = { Text("ئۆي نومۇرى") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = flowAmount,
                    onValueChange = { flowAmount = it },
                    label = { Text("ئېقىم مىقدارى (GB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = openDate,
                    onValueChange = { openDate = it },
                    label = { Text("تىزىملاتقان ۋاقتى") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ئىزاھات") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("VIP ھوقۇقى بېرىش:")
                    Switch(checked = isVip, onCheckedChange = { isVip = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanUser = username.trim().ifEmpty { fullName.trim() }
                    if (cleanUser.isNotEmpty()) {
                        val code = vault.generateVerificationCode(cleanUser, isVip)
                        onSave(
                            ClientAccountRecord(
                                id = initial?.id ?: UUID.randomUUID().toString(),
                                fullName = fullName.trim().ifEmpty { cleanUser },
                                username = cleanUser,
                                password = password.trim().ifEmpty { "123456" },
                                email = initial?.email ?: "$cleanUser@gmail.com",
                                streetName = streetName.trim(),
                                houseNumber = houseNumber.trim(),
                                flowAmount = flowAmount.trim(),
                                openDate = openDate.trim(),
                                notes = notes.trim(),
                                status = if (isVip) "VIP" else "APPROVED",
                                licenseCode = code,
                                isVip = isVip,
                                vipExpireEpochMillis = if (isVip) (initial?.vipExpireEpochMillis?.takeIf { it > 0L } ?: (System.currentTimeMillis() + 30L * 24L * 3600L * 1000L)) else 0L,
                                paidMonths = initial?.paidMonths ?: List(12) { false }
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AdminEmerald)
            ) {
                Text("ساقلاش", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("بىكار قىلىش")
            }
        }
    )
}
