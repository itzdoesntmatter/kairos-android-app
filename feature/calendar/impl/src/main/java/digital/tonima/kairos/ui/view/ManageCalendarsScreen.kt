package digital.tonima.kairos.ui.view

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.AutoMirrored.Filled
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import digital.tonima.core.usecases.ProtonPackages
import digital.tonima.core.viewmodel.ManageCalendarsIntent
import digital.tonima.core.viewmodel.ManageCalendarsViewModel
import digital.tonima.kairos.core.R
import digital.tonima.kairos.core.model.ProtonCalendarStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCalendarsScreen(
    viewModel: ManageCalendarsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val presetColors =
        listOf(
            Color(0xFFF44336),
            Color(0xFFE91E63),
            Color(0xFF9C27B0),
            Color(0xFF673AB7),
            Color(0xFF3F51B5),
            Color(0xFF2196F3),
            Color(0xFF03A9F4),
            Color(0xFF00BCD4),
            Color(0xFF009688),
            Color(0xFF4CAF50),
            Color(0xFF8BC34A),
            Color(0xFFCDDC39),
            Color(0xFFFFEB3B),
            Color(0xFFFFC107),
            Color(0xFFFF9800),
            Color(0xFFFF5722),
        )

    if (state.showEditDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.handleIntent(ManageCalendarsIntent.CloseEditDialog) },
            title = { Text("Editar Calendário") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.editName,
                        onValueChange = { viewModel.handleIntent(ManageCalendarsIntent.UpdateEditName(it)) },
                        label = { Text("Nome do Calendário") },
                    )
                    Text("Cor")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presetColors) { color ->
                            val isSelected = state.editColor == color.toArgb()
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable {
                                            viewModel.handleIntent(
                                                ManageCalendarsIntent.UpdateEditColor(color.toArgb()),
                                            )
                                        }
                                        .border(
                                            width = if (isSelected) 3.dp else 0.dp,
                                            color =
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    Color.Transparent
                                                },
                                            shape = CircleShape,
                                        ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.handleIntent(ManageCalendarsIntent.SaveEdit) }) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.handleIntent(ManageCalendarsIntent.CloseEditDialog) }) {
                    Text("Cancelar")
                }
            },
        )
    }

    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meus Calendários Importados") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Filled.ArrowBack,
                            contentDescription = "Voltar",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            state.protonStatus?.let { status ->
                if (status.protonCalendarVisible || status.protonCalendarAppInstalled || status.syncBridgeInstalled) {
                    ProtonStatusCard(
                        status = status,
                        context = context,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                    )
                }
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.calendars.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum calendário importado encontrado.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.calendars) { calendar ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(calendar.color)),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(text = calendar.displayName, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        viewModel.handleIntent(
                                            ManageCalendarsIntent.OpenEditDialog(calendar),
                                        )
                                    },
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Editar")
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.handleIntent(
                                            ManageCalendarsIntent.DeleteCalendar(calendar.id),
                                        )
                                    },
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Deletar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtonStatusCard(
    status: ProtonCalendarStatus,
    context: Context,
    modifier: Modifier = Modifier,
) {
    if (status.protonCalendarVisible) {
        Card(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.proton_calendar_connected),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    } else {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.proton_calendar_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.proton_calendar_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        if (status.syncBridgeInstalled) {
                            openSyncBridge(context)
                        } else {
                            openSyncBridgePage(context)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (status.syncBridgeInstalled) {
                                R.string.proton_calendar_open_bridge
                            } else {
                                R.string.proton_calendar_get_bridge
                            },
                        ),
                    )
                }
            }
        }
    }
}

private fun openSyncBridge(context: Context) {
    val bridge =
        ProtonPackages.SYNC_BRIDGES.firstOrNull { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }
    val intent = bridge?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent != null) {
        context.startActivity(intent)
    } else {
        openSyncBridgePage(context)
    }
}

private fun openSyncBridgePage(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ProtonPackages.SYNC_BRIDGE_URL))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.cannot_open_event, Toast.LENGTH_SHORT).show()
    }
}
