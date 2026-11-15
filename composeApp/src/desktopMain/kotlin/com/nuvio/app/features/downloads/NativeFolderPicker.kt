package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_download_folder_picker_title
import org.jetbrains.compose.resources.getString
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * The folder picker of the system. On macOS that is the native open panel, asked to choose folders
 * through the AWT property the JDK reads when it opens the panel. Elsewhere a Swing chooser in
 * folder mode, which is the best AWT offers there.
 */
internal object NativeFolderPicker {
    private val lock = Any()

    /** The folder the user chose, or null when the panel was closed without a choice. Blocks until then. */
    fun pick(isMac: Boolean, startAt: File? = null): String? {
        val title = runBlocking { getString(Res.string.settings_download_folder_picker_title) }
        return if (isMac) pickOnMac(title, startAt) else pickWithSwing(title, startAt)
    }

    private fun pickOnMac(title: String, startAt: File?): String? {
        var chosen: String? = null
        val show = {
            // The property is read for each panel and holds for the whole process, so it is set
            // for this panel only.
            synchronized(lock) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                    startAt?.takeIf { it.isDirectory }?.let { dialog.directory = it.absolutePath }
                    dialog.isVisible = true
                    val file = dialog.file
                    if (file != null) chosen = File(dialog.directory.orEmpty(), file).absolutePath
                } finally {
                    System.clearProperty("apple.awt.fileDialogForDirectories")
                }
            }
        }
        if (EventQueue.isDispatchThread()) show() else EventQueue.invokeAndWait(show)
        return chosen
    }

    private fun pickWithSwing(title: String, startAt: File?): String? {
        var chosen: String? = null
        val show = {
            val chooser = JFileChooser(startAt?.takeIf { it.isDirectory }).apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chosen = chooser.selectedFile?.absolutePath
        }
        if (EventQueue.isDispatchThread()) show() else EventQueue.invokeAndWait(show)
        return chosen
    }
}
