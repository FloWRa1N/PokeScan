package de.pokescan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

fun preis(k: Karte): String =
    k.price?.let { String.format(Locale.GERMANY, "%.2f €", it) } ?: "kein Preis verfügbar"

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var tab by remember { mutableStateOf(0) }
    var sammlung by remember { mutableStateOf(store.load()) }

    fun update(l: List<Karte>) {
        sammlung = l
        store.save(l)
    }

    fun hinzu(k: Karte) {
        val da = sammlung.any { it.id == k.id }
        update(
            if (da) sammlung.map { if (it.id == k.id) it.copy(count = it.count + 1) else it }
            else sammlung + k
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = {}, label = { Text("Scannen") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {}, label = { Text("Sammlung (${sammlung.sumOf { it.count }})") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (tab == 0) ScanScreen { hinzu(it) }
            else SammlungScreen(sammlung) { update(it) }
        }
    }
}

@Composable
fun CameraView(modifier: Modifier, onReady: (ImageCapture) -> Unit) {
    val owner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { c ->
            val pv = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(pv.surfaceProvider)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                onReady(capture)
            }, ContextCompat.getMainExecutor(c))
            pv
        }
    )
}

@Composable
fun KarteRow(k: Karte, trailing: @Composable () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = k.image,
                contentDescription = k.name,
                modifier = Modifier.width(64.dp).height(90.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(k.name, style = MaterialTheme.typography.titleMedium)
                Text("${k.setName} · ${k.number}/${k.total}")
                Text(preis(k), style = MaterialTheme.typography.titleSmall)
                trailing()
            }
        }
    }
}

@Composable
fun ScanScreen(onAdd: (Karte) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var name by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Karte>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun suchen() {
        scope.launch {
            busy = true
            status = "Suche…"
            try {
                results = Tcgdex.find(name, num, total)
                status = if (results.isEmpty()) "Keine Karte gefunden. Name oder Nummer anpassen." else ""
            } catch (e: Exception) {
                status = "Fehler: ${e.message}"
            }
            busy = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            if (granted) CameraView(Modifier.fillMaxWidth().height(280.dp)) { capture = it }
            else Text("Kamera-Berechtigung fehlt. Bitte in den App-Einstellungen erlauben.")
        }
        item {
            Button(
                onClick = {
                    val cap = capture
                    if (cap != null) {
                        busy = true
                        status = "Lese Karte…"
                        Scanner.scan(ctx, cap, { g ->
                            name = g.name
                            num = g.number
                            total = g.total
                            busy = false
                            suchen()
                        }, { e ->
                            status = e
                            busy = false
                        })
                    }
                },
                enabled = capture != null && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Karte scannen") }
        }
        item {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Kartenname") }, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = num, onValueChange = { num = it },
                    label = { Text("Nr.") }, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = total, onValueChange = { total = it },
                    label = { Text("von") }, modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { suchen() },
                enabled = name.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Manuell suchen") }
        }
        if (status.isNotEmpty()) item { Text(status) }
        items(results) { k ->
            KarteRow(k) {
                Button(onClick = {
                    onAdd(k)
                    status = "${k.name} hinzugefügt"
                }) { Text("Zur Sammlung") }
            }
        }
    }
}

@Composable
fun SammlungScreen(liste: List<Karte>, onChange: (List<Karte>) -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    val wert = liste.sumOf { (it.price ?: 0.0) * it.count }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Gesamtwert: " + String.format(Locale.GERMANY, "%.2f €", wert),
                style = MaterialTheme.typography.headlineSmall
            )
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val neu = liste.map { k ->
                            val d = Tcgdex.detail(k.id)
                            if (d != null) k.copy(price = d.price) else k
                        }
                        onChange(neu)
                        busy = false
                    }
                },
                enabled = !busy && liste.isNotEmpty()
            ) { Text(if (busy) "Aktualisiere…" else "Preise aktualisieren") }
        }
        items(liste, key = { it.id }) { k ->
            KarteRow(k) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        onChange(liste.mapNotNull {
                            if (it.id != k.id) it
                            else if (it.count > 1) it.copy(count = it.count - 1)
                            else null
                        })
                    }) { Text("−") }
                    Text("${k.count}×")
                    TextButton(onClick = {
                        onChange(liste.map {
                            if (it.id == k.id) it.copy(count = it.count + 1) else it
                        })
                    }) { Text("+") }
                    TextButton(onClick = { uri.openUri(k.cardmarketUrl) }) { Text("Cardmarket") }
                }
            }
        }
    }
}
