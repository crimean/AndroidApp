package com.strikelines.app

import android.net.Uri
import android.os.AsyncTask
import android.provider.OpenableColumns
import com.strikelines.app.utils.PlatformUtil
import net.osmand.aidl.OsmandAidlConstants.*
import net.osmand.aidl.copyfile.CopyFileParams
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.*

class ImportHelper(private val app: StrikeLinesApplication) : ImportTaskListener {

	private var importTask: ImportTask? = null
	var listener: ImportHelperListener? = null

	public fun importFile(uri: Uri): Int {
		return when {
			importTask != null -> {
				RESULT_BUSY
			}
			else -> {
				val importTask = ImportTask(app, uri)
				this.importTask = importTask
				importTask.listener = this
				importTask.execute()
				RESULT_OK
			}
		}
	}

	public fun isCopying(): Boolean = importTask != null

	override fun fileCopyStarted(fileName: String?) {
		listener?.fileCopyStarted(fileName)
	}

	override fun fileCopyFinished(fileName: String?, success: Boolean) {
		importTask = null
		listener?.fileCopyFinished(fileName, success)
	}

	companion object {
		const val RESULT_OK = 0
		const val RESULT_BUSY = -1
	}
}

interface ImportHelperListener {
	fun fileCopyStarted(fileName: String?)
	fun fileCopyFinished(fileName: String?, success: Boolean)
}

private class ImportTask(
	val app: StrikeLinesApplication,
	val uri: Uri
) : AsyncTask<Void, Void, Boolean>() {

	private val log = PlatformUtil.getLog(ImportTask::class.java)
	private val fileName: String?
	var copying = false
		private set
	var listener: ImportTaskListener? = null

	init {
		fileName = getNameFromContentUri(uri)
	}

	companion object {
		const val SQLITE_EXT = ".sqlitedb"
		const val CHARTS_EXT = ".charts"

		const val BUFFER_SIZE = COPY_FILE_PART_SIZE_LIMIT
		const val MAX_RETRY_COUNT = 10
	}

	override fun onPreExecute() {
		super.onPreExecute()
		if (fileName != null && fileName.isNotEmpty()) {
			copying = true
			listener?.fileCopyStarted(fileName)
		}
	}

	override fun doInBackground(vararg params: Void): Boolean? {
		return if (fileName != null && fileName.isNotEmpty()) {
			handleFileImport()
		} else {
			false
		}
	}

	override fun onPostExecute(result: Boolean?) {
		super.onPostExecute(result)
		if (result != null && result) {
			listener?.fileCopyFinished(fileName, true)
		} else {
			listener?.fileCopyFinished(fileName, false)
		}
	}

	private fun handleFileImport(): Boolean {
		if (fileName != null && fileName.isNotEmpty()) {
			if (fileName.endsWith(SQLITE_EXT)) {
				return fileImportImpl(uri, fileName)
			} else if (fileName.endsWith(CHARTS_EXT)) {
				val newFilename = fileName.removePrefix(CHARTS_EXT) + SQLITE_EXT
				return fileImportImpl(uri, newFilename)
			}
		}
		return false
	}

	private fun fileImportImpl(uri: Uri, fileName: String): Boolean {
		var isError = false
		var data = ByteArray(BUFFER_SIZE.toInt())
		val retryInterval = COPY_FILE_MAX_LOCK_TIME_MS / 3
		val startTime = System.currentTimeMillis()
		try {
			val bis: DataInputStream = if (uri.scheme == "content") {
				DataInputStream(app.contentResolver.openInputStream(uri))
			} else {
				DataInputStream(
					FileInputStream(
						app.applicationContext.contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor
					)
				)
			}
			var retryCounter = 0
			var response = OK_RESPONSE
			var read = 0
			while (read != -1 && !isError) {
				when (response) {
					OK_RESPONSE -> {
						read = bis.read(data)
						if (read > 0 && read < data.size) {
							data = Arrays.copyOf(data, read)
						} else if (read <= 0) {
							data = ByteArray(0)
						}
					}
					COPY_FILE_WRITE_LOCK_ERROR -> {
						if (retryCounter < MAX_RETRY_COUNT) {
							log.error("File is writing by another process. Retry in $retryInterval ms.")
							retryCounter++
							Thread.sleep(retryInterval)
						} else {
							log.error("File is writing by another process. Stop.")
							isError = true
						}
					}
					COPY_FILE_PARAMS_ERROR -> {
						log.error("Illegal parameters")
						isError = true
					}
					COPY_FILE_PART_SIZE_LIMIT_ERROR -> {
						log.error("File part size must no exceed $COPY_FILE_PART_SIZE_LIMIT bytes")
						isError = true
					}
					COPY_FILE_IO_ERROR -> {
						log.error("I/O Error")
						isError = true
					}
					COPY_FILE_UNSUPPORTED_FILE_TYPE_ERROR -> {
						log.error("Unsupported file type")
						isError = true
					}
				}
				if (!isError) {
					response = app.osmandHelper.copyFile(CopyFileParams(fileName, data, startTime, read == -1))
				}
			}
			bis.close()
			return !isError

		} catch (e: IOException) {
			log.error(e.message, e)
			return false
		}
	}

	private fun getNameFromContentUri(contentUri: Uri): String? {
		val returnCursor = app.contentResolver.query(contentUri, null, null, null, null)
		val name = if (returnCursor != null && returnCursor.moveToFirst()) {
			val columnIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
			if (columnIndex != -1) {
				returnCursor.getString(columnIndex)
			} else {
				contentUri.lastPathSegment
			}
		} else {
			null
		}
		if (returnCursor != null && !returnCursor.isClosed) {
			returnCursor.close()
		}
		return name
	}
}

private interface ImportTaskListener {
	fun fileCopyStarted(fileName: String?)
	fun fileCopyFinished(fileName: String?, success: Boolean)
}