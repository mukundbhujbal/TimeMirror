package com.mukundbhujbal.timemirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.engine.TimerEngine
import com.mukundbhujbal.timemirror.service.TimerOverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appPreferences: AppPreferences,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isMonitoringActive by appPreferences.isMonitoringActiveFlow.collectAsState()

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }
    var isBackgroundRestricted by remember {
        mutableStateOf(PermissionHelper.isBackgroundRestricted(context))
    }
    var isPowerSaveMode by remember {
        mutableStateOf(PermissionHelper.isPowerSaveMode(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = PermissionHelper.isIgnoringBatteryOptimizations(context)
                isBackgroundRestricted = PermissionHelper.isBackgroundRestricted(context)
                isPowerSaveMode = PermissionHelper.isPowerSaveMode(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Monitoring Service Control
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Monitoring Service",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Status Indication
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isMonitoringActive) Color(0xFF4CAF50) else Color.Gray
                                )
                        )
                        Text(
                            text = if (isMonitoringActive) "Service Status: Running" else "Service Status: Stopped",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isMonitoringActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = if (isMonitoringActive) {
                            "TimeAware is actively monitoring your selected apps in the background and recording screen time."
                        } else {
                            "The background monitoring service is currently stopped. No screen-time tracking or watermarks are active."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isMonitoringActive) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                // 1. Safely restore timer's hidden state to Resume / Show
                                appPreferences.resumeTimerNow()
                                // 2. Stop monitoring service and pause timer
                                appPreferences.setMonitoringActive(false)
                                TimerOverlayService.stopService(context)
                                TimerEngine.pause()
                                onBackClick()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Stop Monitoring Service",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Section 2: Background Reliability
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Background Reliability",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Help understand whether Android is currently applying battery or background restrictions that could affect TimeAware's monitoring service.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    // Status 1: Battery Optimization
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Battery Optimization",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isIgnoringBatteryOptimizations) Color(0xFF4CAF50) else Color(0xFFE53935)
                                    )
                            )
                            Text(
                                text = if (isIgnoringBatteryOptimizations) {
                                    "Battery optimization is not restricting TimeAware"
                                } else {
                                    "Battery optimization is active"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnoringBatteryOptimizations) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                        }
                    }

                    // Status 2: Background Restriction
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Background Restriction",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isBackgroundRestricted) Color(0xFFE53935) else Color(0xFF4CAF50)
                                    )
                            )
                            Text(
                                text = if (isBackgroundRestricted) {
                                    "Background activity is restricted"
                                } else {
                                    "Background activity is not restricted"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBackgroundRestricted) Color(0xFFC62828) else Color(0xFF2E7D32)
                            )
                        }
                    }

                    // Status 3: Battery Saver
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Battery Saver",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Text(
                                text = if (isPowerSaveMode) {
                                    "Battery Saver is ON"
                                } else {
                                    "Battery Saver is OFF"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                PermissionHelper.openBatteryOptimizationSettings(context)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Open Battery Settings",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                PermissionHelper.openAppDetailsSettings(context)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Open App Settings",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Section 3: User Profile (User Name)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "User Profile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Enter your name to appear on TimeAware Screen Time Reports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val keyboardController = LocalSoftwareKeyboardController.current
                    val focusManager = LocalFocusManager.current

                    val initialSavedName = remember { appPreferences.getUserName() }
                    var savedName by remember { mutableStateOf(initialSavedName) }
                    var nameInput by remember { mutableStateOf(initialSavedName) }
                    var isEditing by remember { mutableStateOf(initialSavedName.isEmpty()) }

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { newValue ->
                            if (isEditing) {
                                nameInput = newValue
                            }
                        },
                        readOnly = !isEditing,
                        label = { Text("User Name (Optional)") },
                        placeholder = { Text("Enter your name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val clean = nameInput.trim()
                                savedName = clean
                                nameInput = clean
                                appPreferences.setUserName(clean)
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            when {
                                !isEditing -> {
                                    // Confirmed mode: Checkmark icon -> Tapping returns to edit mode (icon becomes X)
                                    IconButton(onClick = {
                                        isEditing = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Edit name",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                nameInput == savedName && nameInput.isNotEmpty() -> {
                                    // In Edit mode with saved name: Clear icon -> Tapping clears the name
                                    IconButton(onClick = {
                                        nameInput = ""
                                        savedName = ""
                                        appPreferences.setUserName("")
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear name",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                nameInput.isNotEmpty() -> {
                                    // In Edit mode after typing: Checkmark icon -> Tapping confirms/saves
                                    IconButton(onClick = {
                                        val clean = nameInput.trim()
                                        savedName = clean
                                        nameInput = clean
                                        appPreferences.setUserName(clean)
                                        isEditing = false
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Confirm name",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Section 3: About TimeAware – भान
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "About TimeAware – भान",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "TimeAware - भान is a conscious screen-time companion designed to cultivate mindful device usage through real-time awareness watermarks and insightful daily history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    InfoRow(label = "Application", value = "TimeAware – भान")
                    InfoRow(label = "Version", value = "1.0")
                    InfoRow(label = "Developer", value = "Mukund Bhujbal")
                    InfoRow(label = "Developer Email", value = "To be added")
                    InfoRow(label = "Developer Phone", value = "To be added")
                    InfoRow(label = "Copyright", value = "© 2026 TimeAware")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
