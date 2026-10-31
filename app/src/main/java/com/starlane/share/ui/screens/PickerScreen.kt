package com.starlane.share.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starlane.share.R
import com.starlane.share.apps.AppEntry
import com.starlane.share.apps.AppPackages
import com.starlane.share.engine.ShareManager
import com.starlane.share.files.MediaKind
import com.starlane.share.files.MediaQuery
import com.starlane.share.files.PickItem
import com.starlane.share.files.Selection
import com.starlane.share.files.UriInfo
import com.starlane.share.ui.Chip
import com.starlane.share.ui.EmptyState
import com.starlane.share.ui.Fmt
import com.starlane.share.ui.Glyph
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.Perms
import com.starlane.share.ui.PrimaryButton
import com.starlane.share.ui.SecondaryButton
import com.starlane.share.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PickTab(val label: Int, val glyph: Glyph) {
    PHOTOS(R.string.tab_photos, Glyph.IMAGE), VIDEOS(R.string.tab_videos, Glyph.VIDEO), AUDIO(R.string.tab_audio, Glyph.AUDIO),
    DOCUMENTS(R.string.tab_documents, Glyph.DOC), APPS(R.string.tab_apps, Glyph.APP), DOWNLOADS(R.string.tab_downloads, Glyph.DOWNLOAD),
    OTHER(R.string.tab_other, Glyph.FILE), FOLDERS(R.string.tab_folders, Glyph.FOLDER),
}

enum class SortBy { DATE, NAME, SIZE }

/** Content picker: categories, multi-select, select all, search, sorting, sizes and summary. */
@Composable
fun PickerScreen(onBack: () -> Unit, onOpenApp: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(PickTab.PHOTOS) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortBy.DATE) }
    var showSystem by remember { mutableStateOf(false) }
    var mediaGranted by remember { mutableStateOf(Perms.granted(ctx, Perms.media())) }
    val conn by ShareManager.connection.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { mediaGranted = Perms.granted(ctx, Perms.media()) }
    val docsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { Selection.addAll(withContext(Dispatchers.IO) { uris.mapNotNull { UriInfo.describe(ctx, it) } }) }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch { Selection.addAll(withContext(Dispatchers.IO) { MediaQuery.listTree(ctx, uri) }) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.pick_title), onBack)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickTab.entries.forEach { t -> Chip(stringResource(t.label), tab == t, { tab = t; query = "" }) }
        }
        Spacer(Modifier.height(8.dp))
        val searchable = tab in listOf(PickTab.PHOTOS, PickTab.VIDEOS, PickTab.AUDIO, PickTab.DOCUMENTS, PickTab.APPS)
        if (searchable) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(stringResource(R.string.pick_search)) },
                    leadingIcon = { GlyphIcon(Glyph.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp) },
                )
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.pick_sort_date), sort == SortBy.DATE, { sort = SortBy.DATE })
                Chip(stringResource(R.string.pick_sort_name), sort == SortBy.NAME, { sort = SortBy.NAME })
                Chip(stringResource(R.string.pick_sort_size), sort == SortBy.SIZE, { sort = SortBy.SIZE })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PickTab.PHOTOS, PickTab.VIDEOS, PickTab.AUDIO, PickTab.DOCUMENTS -> {
                    val kind = when (tab) { PickTab.PHOTOS -> MediaKind.PHOTOS; PickTab.VIDEOS -> MediaKind.VIDEOS; PickTab.AUDIO -> MediaKind.AUDIO; else -> MediaKind.DOCUMENTS }
                    if (tab == PickTab.DOCUMENTS && Build.VERSION.SDK_INT >= 30) {
                        BrowseCard(stringResource(R.string.pick_browse_files), stringResource(R.string.pick_browse_files_hint), Glyph.DOC) {
                            docsLauncher.launch(arrayOf("application/pdf", "text/*", "application/msword", "application/vnd.*", "application/zip", "application/epub+zip"))
                        }
                    } else if (!mediaGranted) {
                        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(tab.glyph, stringResource(R.string.pick_media_permission))
                            PrimaryButton(stringResource(R.string.action_grant), { permLauncher.launch(Perms.media()) })
                            Spacer(Modifier.height(12.dp))
                            SecondaryButton(stringResource(R.string.pick_browse_files), { docsLauncher.launch(arrayOf("*/*")) })
                        }
                    } else MediaList(kind, query, sort)
                }
                PickTab.APPS -> AppList(query, sort, showSystem, { showSystem = it }, onOpenApp)
                PickTab.DOWNLOADS -> BrowseCard(stringResource(R.string.tab_downloads), stringResource(R.string.pick_downloads_hint), Glyph.DOWNLOAD) {
                    docsLauncher.launch(arrayOf("*/*"))
                }
                PickTab.OTHER -> BrowseCard(stringResource(R.string.pick_browse_files), stringResource(R.string.pick_browse_files_hint), Glyph.FILE) {
                    docsLauncher.launch(arrayOf("*/*"))
                }
                PickTab.FOLDERS -> BrowseCard(stringResource(R.string.pick_browse_folder), stringResource(R.string.pick_browse_folder_hint), Glyph.FOLDER) {
                    treeLauncher.launch(null)
                }
            }
        }

        // Selection summary + send.
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            if (Selection.count > 0) {
                SelectedStrip()
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.pick_summary, pluralStringResource(R.plurals.files_count, Selection.count, Selection.count), Fmt.bytes(ctx, Selection.totalBytes)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (conn == null) Text(stringResource(R.string.pick_not_connected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (Selection.count > 0) {
                    Text(stringResource(R.string.action_clear_selection), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Selection.clear() }.padding(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                if (conn != null) stringResource(R.string.pick_send_to, conn!!.peerName) else stringResource(R.string.home_start_scanning),
                {
                    if (conn != null) {
                        val (items, groups) = Selection.build()
                        if (ShareManager.send(items, groups)) Selection.clear()
                    } else ShareManager.emit(com.starlane.share.engine.UiEvent.Message(com.starlane.share.engine.Msg.NOT_CONNECTED))
                },
                Modifier.fillMaxWidth(), enabled = Selection.count > 0, glyph = Glyph.SEND,
            )
        }
    }
}

@Composable
private fun SelectedStrip() {
    val ctx = LocalContext.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Selection.apps.forEach { (pkg, v) ->
            SmallSelChip(v.second.meta["label"] ?: pkg, Fmt.bytes(ctx, v.first.sumOf { it.size })) { Selection.apps.remove(pkg) }
        }
        Selection.items.take(30).forEach { p -> SmallSelChip(p.name, Fmt.bytes(ctx, p.size)) { Selection.toggle(p) } }
    }
}

@Composable
private fun SmallSelChip(name: String, size: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onRemove).padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$name · $size", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
        GlyphIcon(Glyph.CLOSE, MaterialTheme.colorScheme.onSurfaceVariant, 14.dp)
    }
}

@Composable
private fun BrowseCard(title: String, hint: String, g: Glyph, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(g, hint)
        PrimaryButton(title, onClick, glyph = Glyph.FOLDER)
    }
}

private fun <T> sorted(list: List<T>, sort: SortBy, name: (T) -> String, size: (T) -> Long, date: (T) -> Long) = when (sort) {
    SortBy.NAME -> list.sortedBy { name(it).lowercase() }
    SortBy.SIZE -> list.sortedByDescending(size)
    SortBy.DATE -> list.sortedByDescending(date)
}

@Composable
private fun MediaList(kind: MediaKind, query: String, sort: SortBy) {
    val ctx = LocalContext.current
    val all by produceState<List<PickItem>?>(null, kind) { value = withContext(Dispatchers.IO) { MediaQuery.load(ctx, kind) } }
    val list = all
    if (list == null) { Text(stringResource(R.string.pick_loading), Modifier.padding(24.dp)); return }
    val shown = remember(list, query, sort) {
        sorted(list.filter { query.isBlank() || it.name.contains(query, true) }, sort, { it.name }, { it.size }, { it.lastModified })
    }
    if (shown.isEmpty()) { EmptyState(Glyph.FILE, stringResource(R.string.pick_empty)); return }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            val allSel = shown.all { Selection.contains(it) }
            Text(
                stringResource(if (allSel) R.string.action_clear_selection else R.string.action_select_all), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { if (allSel) Selection.removeAll(shown) else Selection.addAll(shown) }.padding(8.dp),
            )
        }
        if (kind == MediaKind.PHOTOS || kind == MediaKind.VIDEOS) {
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                items(shown, key = { it.uri.toString() }) { p -> MediaTile(p, kind == MediaKind.VIDEOS) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.uri.toString() }) { p -> FileRow(p, if (kind == MediaKind.AUDIO) Glyph.AUDIO else Glyph.DOC) }
            }
        }
    }
}

@Composable
private fun MediaTile(p: PickItem, video: Boolean) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 104.dp.roundToPx() }
    val bmp by produceState(MediaQuery.cachedThumb(p.uri!!), p.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { MediaQuery.thumb(ctx, p.uri, video, px) }
    }
    val sel = Selection.contains(p)
    Box(
        Modifier.padding(3.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox) { Selection.toggle(p) },
    ) {
        bmp?.let { Image(it.asImageBitmap(), p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: GlyphIcon(if (video) Glyph.VIDEO else Glyph.IMAGE, MaterialTheme.colorScheme.onSurfaceVariant, 28.dp, Modifier.align(Alignment.Center))
        Text(
            Fmt.bytes(ctx, p.size), color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (sel) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.CHECK, Color.White, 14.dp)
        }
    }
}

@Composable
private fun FileRow(p: PickItem, g: Glyph) {
    val ctx = LocalContext.current
    val sel = Selection.contains(p)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { Selection.toggle(p) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(g, MaterialTheme.colorScheme.primary, 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.bytes(ctx, p.size)} · ${p.mime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Checkbox(sel, { Selection.toggle(p) })
    }
}

@Composable
private fun AppList(query: String, sort: SortBy, showSystem: Boolean, setShowSystem: (Boolean) -> Unit, onOpenApp: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null, showSystem) { value = withContext(Dispatchers.IO) { AppPackages.list(ctx, showSystem) } }
    val list = apps
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pick_system_apps), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(showSystem, setShowSystem)
        }
        if (list == null) { Text(stringResource(R.string.pick_loading), Modifier.padding(24.dp)); return@Column }
        val shown = remember(list, query, sort) {
            sorted(list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }, sort, { it.label }, { it.apkSize }, { it.installedAt })
        }
        if (shown.isEmpty()) { EmptyState(Glyph.APP, stringResource(R.string.pick_empty)); return@Column }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { a -> AppRow(a) { onOpenApp(a) } }
        }
    }
}

@Composable
private fun AppRow(a: AppEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<android.graphics.Bitmap?>(null, a.packageName) { value = withContext(Dispatchers.IO) { AppPackages.icon(ctx, a.packageName, px) } }
    val selected = Selection.apps.containsKey(a.packageName)
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            icon?.let { Image(it.asImageBitmap(), a.label, Modifier.fillMaxSize()) } ?: GlyphIcon(Glyph.APP, MaterialTheme.colorScheme.primary, 24.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.app_version, a.versionName, Fmt.bytes(ctx, a.apkSize)) + if (a.splitCount > 0) " · +${a.splitCount}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (selected) GlyphIcon(Glyph.CHECK, MaterialTheme.colorScheme.secondary, 22.dp)
    }
}

/** Unused-import guard for LaunchedEffect in some compiler versions. */
@Suppress("unused") private val keep: (@Composable () -> Unit) = { LaunchedEffect(Unit) {} }
