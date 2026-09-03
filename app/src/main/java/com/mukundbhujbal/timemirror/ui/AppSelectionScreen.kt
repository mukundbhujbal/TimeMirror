package com.mukundbhujbal.timemirror.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mukundbhujbal.timemirror.data.AppInfo
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.service.TimerOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(
    appPreferences: AppPreferences,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val monitoredPackages by appPreferences.monitoredPackagesFlow.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val apps = loadInstalledApps(context, monitoredPackages)
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    val filteredApps = remember(installedApps, searchQuery, monitoredPackages) {
        installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Apps to Monitor") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text("Search installed apps...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${monitoredPackages.size} app(s) selected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val allPkgs = installedApps.map { it.packageName }.toSet()
                            appPreferences.setMonitoredPackages(allPkgs)
                            TimerOverlayService.notifyConfigChanged(context)
                        }
                    ) {
                        Text("Select All")
                    }
                    TextButton(
                        onClick = {
                            appPreferences.setMonitoredPackages(emptySet())
                            TimerOverlayService.notifyConfigChanged(context)
                        }
                    ) {
                        Text("Clear")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) "No launchable apps found" else "No matching apps",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { it.packageName }
                    ) { app ->
                        val isChecked = monitoredPackages.contains(app.packageName)
                        AppItemRow(
                            app = app,
                            isChecked = isChecked,
                            onToggle = { checked ->
                                appPreferences.toggleAppMonitored(app.packageName, checked)
                                TimerOverlayService.notifyConfigChanged(context)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppItemRow(
    app: AppInfo,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isChecked) },
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            app.icon?.let { drawable ->
                val bitmap = remember(drawable) { drawableToBitmap(drawable) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.size(40.dp)
                    )
                }
            } ?: Spacer(modifier = Modifier.size(40.dp))

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle(it) }
            )
        }
    }
}

private fun loadInstalledApps(context: Context, monitored: Set<String>): List<AppInfo> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
    val appList = mutableListOf<AppInfo>()
    val selfPkg = context.packageName

    for (info in resolveInfos) {
        val pkg = info.activityInfo.packageName
        if (pkg != selfPkg) {
            val label = info.loadLabel(pm).toString()
            val icon = info.loadIcon(pm)
            appList.add(
                AppInfo(
                    packageName = pkg,
                    appName = label,
                    icon = icon,
                    isMonitored = monitored.contains(pkg)
                )
            )
        }
    }

    return appList.distinctBy { it.packageName }
        .sortedBy { it.appName.lowercase() }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    try {
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    } catch (e: Exception) {
        return null
    }
}
