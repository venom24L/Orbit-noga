package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.data.ArtworkEntry
import com.example.data.OrbitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StudioCollectionSection(
    context: Context,
    repository: OrbitRepository,
    accentColor: Color,
    isServiceRunning: Boolean,
    onNavigateToCanvas: () -> Unit,
    onNavigateToUpload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val allArtworks by repository.studioArtworks.collectAsState(initial = emptyList<ArtworkEntry>())

    var selectedFilter by remember { mutableStateOf("all") } // all, canvas, photo, favorites
    var selectedArtworkForDetail by remember { mutableStateOf<ArtworkEntry?>(null) }
    var artworkToDelete by remember { mutableStateOf<ArtworkEntry?>(null) }

    val filteredArtworks: List<ArtworkEntry> = remember(allArtworks, selectedFilter) {
        when (selectedFilter) {
            "canvas" -> allArtworks.filter { it.type.equals("CANVAS", ignoreCase = true) }
            "photo" -> allArtworks.filter { it.type.equals("PHOTO_CROP", ignoreCase = true) || it.type.equals("PHOTO", ignoreCase = true) }
            "favorites" -> allArtworks.filter { it.isFavorite }
            else -> allArtworks
        }
    }

    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        // Filter Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChipItem(
                label = "${stringResource(id = R.string.filter_all)} (${allArtworks.size})",
                isSelected = selectedFilter == "all",
                accentColor = signalOrange,
                onClick = { selectedFilter = "all" }
            )
            FilterChipItem(
                label = stringResource(id = R.string.filter_canvas),
                isSelected = selectedFilter == "canvas",
                accentColor = signalOrange,
                onClick = { selectedFilter = "canvas" }
            )
            FilterChipItem(
                label = stringResource(id = R.string.filter_photos),
                isSelected = selectedFilter == "photo",
                accentColor = signalOrange,
                onClick = { selectedFilter = "photo" }
            )
            FilterChipItem(
                label = stringResource(id = R.string.filter_favorites),
                isSelected = selectedFilter == "favorites",
                accentColor = signalOrange,
                onClick = { selectedFilter = "favorites" }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Empty State or Gallery Grid
        if (filteredArtworks.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0E15)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(signalOrange.copy(alpha = 0.12f), CircleShape)
                            .border(1.dp, signalOrange.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = null,
                            tint = signalOrange,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(id = R.string.collection_empty_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = inkLight
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(id = R.string.collection_empty_desc),
                        fontSize = 12.sp,
                        color = inkDim,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onNavigateToCanvas,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = signalOrange,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Brush,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(id = R.string.open_canvas), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onNavigateToUpload,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(id = R.string.upload_photo), fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        } else {
            // Visual Gallery Adaptive Grid (2-3 Columns responsive)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 2400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = filteredArtworks,
                    key = { it.id }
                ) { artwork ->
                    ArtworkCard(
                        artwork = artwork,
                        accentColor = signalOrange,
                        onCardClick = { selectedArtworkForDetail = artwork },
                        onToggleFavorite = {
                            coroutineScope.launch(Dispatchers.IO) {
                                repository.updateArtwork(artwork.copy(isFavorite = !artwork.isFavorite))
                            }
                        },
                        onApplyToBubble = {
                            applyArtworkToBubble(context, artwork, isServiceRunning)
                        },
                        onDelete = { artworkToDelete = artwork }
                    )
                }
            }
        }
    }

    // Detail & Fullscreen Inspection Dialog
    if (selectedArtworkForDetail != null) {
        ArtworkDetailDialog(
            artwork = selectedArtworkForDetail!!,
            context = context,
            accentColor = signalOrange,
            isServiceRunning = isServiceRunning,
            onDismiss = { selectedArtworkForDetail = null },
            onToggleFavorite = {
                val updated = selectedArtworkForDetail!!.copy(isFavorite = !selectedArtworkForDetail!!.isFavorite)
                selectedArtworkForDetail = updated
                coroutineScope.launch(Dispatchers.IO) {
                    repository.updateArtwork(updated)
                }
            },
            onDelete = {
                val toDelete = selectedArtworkForDetail!!
                selectedArtworkForDetail = null
                artworkToDelete = toDelete
            }
        )
    }

    // Delete Confirmation Dialog
    if (artworkToDelete != null) {
        AlertDialog(
            onDismissRequest = { artworkToDelete = null },
            containerColor = Color(0xFF131520),
            title = {
                Text(
                    text = stringResource(id = R.string.delete_artwork),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = stringResource(id = R.string.delete_artwork_confirm),
                    color = Color(0xFF8E94A8),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val entry = artworkToDelete!!
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val file = File(entry.filePath)
                                if (file.exists()) file.delete()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            repository.deleteArtwork(entry)
                        }
                        artworkToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
                ) {
                    Text(text = "Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { artworkToDelete = null }) {
                    Text(text = stringResource(id = R.string.cancel), color = Color(0xFF8E94A8))
                }
            }
        )
    }
}

@Composable
fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (isSelected) accentColor else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) accentColor else Color(0xFF8E94A8)
        )
    }
}

@Composable
fun ArtworkCard(
    artwork: ArtworkEntry,
    accentColor: Color,
    onCardClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onApplyToBubble: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(artwork.filePath) {
        try {
            BitmapFactory.decodeFile(artwork.filePath)
        } catch (e: Exception) {
            null
        }
    }

    val dateFormatter = remember {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    }

    val isFavorite = artwork.isFavorite
    val favoriteColor by animateColorAsState(
        targetValue = if (isFavorite) Color(0xFFFF2A85) else Color.White.copy(alpha = 0.8f),
        label = "favoriteColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
            .clickable { onCardClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1017)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp, pressedElevation = 8.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Thumbnail container with overlays
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(136.dp)
                    .background(Color(0xFF07080D)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = artwork.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = Color.Gray.copy(alpha = 0.6f),
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Type Badge (top left)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 6.dp, vertical = 2.5.dp)
                ) {
                    val isCanvas = artwork.type.equals("CANVAS", ignoreCase = true)
                    Text(
                        text = if (isCanvas) "CANVAS" else "PHOTO",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCanvas) accentColor else Color(0xFF00E5FF),
                        letterSpacing = 0.5.sp
                    )
                }

                // Favorite Heart Button (top right)
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = favoriteColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Info and Quick Actions
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = artwork.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateFormatter.format(Date(artwork.createdAt)),
                    fontSize = 10.5.sp,
                    color = Color(0xFF6B728E)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Apply to Bubble Quick Button
                Button(
                    onClick = onApplyToBubble,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor.copy(alpha = 0.18f),
                        contentColor = accentColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Use on Bubble",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ArtworkDetailDialog(
    artwork: ArtworkEntry,
    context: Context,
    accentColor: Color,
    isServiceRunning: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(artwork.filePath) {
        try {
            BitmapFactory.decodeFile(artwork.filePath)
        } catch (e: Exception) {
            null
        }
    }

    val dateFormatter = remember {
        SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.getDefault())
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Action Bar in Dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isCanvas = artwork.type.equals("CANVAS", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isCanvas) "CANVAS ART" else "PHOTO CROP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (artwork.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (artwork.isFavorite) Color(0xFFFF2A85) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                shareArtworkImage(context, artwork)
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // High-Resolution Preview Canvas Card
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF050508))
                        .border(2.dp, accentColor.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = artwork.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title and Metadata
                Text(
                    text = artwork.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = dateFormatter.format(Date(artwork.createdAt)),
                    fontSize = 11.sp,
                    color = Color(0xFF8E94A8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Dialog Actions
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Apply to Floating Bubble
                    Button(
                        onClick = {
                            applyArtworkToBubble(context, artwork, isServiceRunning)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RadioButtonChecked,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.apply_as_bubble),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }

                    // Export to device gallery
                    OutlinedButton(
                        onClick = {
                            exportArtworkToGallery(context, artwork)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.export_to_gallery),
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    // Close Button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(id = R.string.overlay_close),
                            color = Color(0xFF8E94A8),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

fun applyArtworkToBubble(context: Context, artwork: ArtworkEntry, isServiceRunning: Boolean) {
    try {
        val srcFile = File(artwork.filePath)
        if (!srcFile.exists()) {
            Toast.makeText(context, "Artwork file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val destFile = File(context.filesDir, "custom_bubble_icon.png")
        srcFile.copyTo(destFile, overwrite = true)

        ThemePreferences.setBubbleSymbol(context, "custom")
        Toast.makeText(context, context.getString(R.string.applied_to_bubble), Toast.LENGTH_SHORT).show()

        if (isServiceRunning) {
            val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                action = FloatingLauncherService.ACTION_UPDATE_THEME
            }
            context.startService(serviceIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to apply bubble icon", Toast.LENGTH_SHORT).show()
    }
}

fun exportArtworkToGallery(context: Context, artwork: ArtworkEntry) {
    try {
        val bitmap = BitmapFactory.decodeFile(artwork.filePath)
        if (bitmap == null) {
            Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val filename = "Orbit_${artwork.title.replace(" ", "_")}_${System.currentTimeMillis()}.png"
        var fos: OutputStream? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Orbit")
            }
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                fos = resolver.openOutputStream(imageUri)
            }
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + "/Orbit"
            val dir = File(imagesDir)
            if (!dir.exists()) dir.mkdirs()
            val image = File(dir, filename)
            fos = FileOutputStream(image)
        }

        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            Toast.makeText(context, context.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
    }
}

fun shareArtworkImage(context: Context, artwork: ArtworkEntry) {
    try {
        val imageFile = File(artwork.filePath)
        if (!imageFile.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, artwork.title)
            putExtra(Intent.EXTRA_TEXT, "Created with Orbit Launcher • ${artwork.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_artwork)))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to share image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
