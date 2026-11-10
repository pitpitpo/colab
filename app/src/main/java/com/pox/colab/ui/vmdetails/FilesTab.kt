package com.pox.colab.ui.vmdetails

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.engine.FileManagerEngine
import com.pox.colab.model.ColabVM
import com.pox.colab.model.RemoteFile
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.ColabYellow
import com.pox.colab.theme.TerminalBackground
import com.pox.colab.theme.TerminalForeground
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(
    vm: ColabVM
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileEngine = remember(vm.id) {
        FileManagerEngine(context, vm)
    }

    val currentPath by fileEngine.currentPath.collectAsState()
    val files by fileEngine.files.collectAsState()
    val isLoading by fileEngine.isLoading.collectAsState()
    val statusNotice by fileEngine.statusNotice.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFileForPreview by remember { mutableStateOf<RemoteFile?>(null) }
    var previewContent by remember { mutableStateOf("") }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(statusNotice) {
        statusNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            fileEngine.clearStatusNotice()
        }
    }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Path Breadcrumbs & Navigation Bar
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { fileEngine.navigateUp() },
                    enabled = currentPath != "/"
                ) {
                    Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = "Go Up")
                }

                // Breadcrumbs
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "colab:$currentPath",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = ColabOrange
                    )
                }

                IconButton(onClick = { fileEngine.refreshDirectory() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                }

                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                }

                IconButton(onClick = { showUploadDialog = true }) {
                    Icon(imageVector = Icons.Default.Upload, contentDescription = "Upload", tint = ColabOrange)
                }
            }
        }

        // Search Filter
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search files in current directory...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(10.dp)
        )

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ColabOrange)
        }

        // Files List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredFiles, key = { it.path }) { file ->
                FileItemRow(
                    file = file,
                    onOpen = {
                        if (file.isDirectory) {
                            fileEngine.navigateTo(file.path)
                        } else {
                            scope.launch {
                                previewContent = fileEngine.readFileContent(file)
                                selectedFileForPreview = file
                            }
                        }
                    },
                    onDownload = {
                        scope.launch {
                            fileEngine.downloadFileToDevice(file)
                        }
                    },
                    onPreview = {
                        scope.launch {
                            previewContent = fileEngine.readFileContent(file)
                            selectedFileForPreview = file
                        }
                    },
                    onRename = { fileToRename = file },
                    onDelete = { fileEngine.deleteFile(file) }
                )
            }
        }
    }

    // File Preview Modal Dialog
    selectedFileForPreview?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedFileForPreview = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = file.formattedSize(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Surface(
                    color = TerminalBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = previewContent,
                            color = TerminalForeground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            fileEngine.downloadFileToDevice(file)
                            selectedFileForPreview = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Download to Device")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedFileForPreview = null }) {
                    Text("Close")
                }
            }
        )
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            fileEngine.createFolder(folderName.trim())
                            showNewFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Upload File Dialog
    if (showUploadDialog) {
        var uploadFileName by remember { mutableStateOf("script.py") }
        var uploadFileContent by remember { mutableStateOf("# Code created from Colab Mobile App\nprint('Hello from Android')\n") }
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("Upload File to Colab") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = uploadFileName,
                        onValueChange = { uploadFileName = it },
                        label = { Text("File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uploadFileContent,
                        onValueChange = { uploadFileContent = it },
                        label = { Text("Content / Script") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (uploadFileName.isNotBlank()) {
                            scope.launch {
                                fileEngine.uploadFile(uploadFileName.trim(), uploadFileContent)
                                showUploadDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                ) {
                    Text("Upload")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Dialog
    fileToRename?.let { file ->
        var newName by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            fileEngine.renameFile(file, newName.trim())
                            fileToRename = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FileItemRow(
    file: RemoteFile,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = when {
                        file.isDirectory -> Icons.Default.Folder
                        file.name.endsWith(".pt") || file.name.endsWith(".safetensors") -> Icons.Default.Storage
                        file.isTextOrCode -> Icons.Default.Code
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    tint = when {
                        file.isDirectory -> ColabYellow
                        file.name.endsWith(".pt") || file.name.endsWith(".safetensors") -> TerminalGreen
                        file.isTextOrCode -> ColabOrange
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Text(
                        text = if (file.isDirectory) "Folder" else file.formattedSize(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!file.isDirectory) {
                    IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = ColabOrange,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (!file.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    menuExpanded = false
                                    onDownload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Preview Content") },
                                onClick = {
                                    menuExpanded = false
                                    onPreview()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = TerminalRed) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
