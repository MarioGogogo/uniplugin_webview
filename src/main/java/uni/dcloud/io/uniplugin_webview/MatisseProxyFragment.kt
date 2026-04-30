package uni.dcloud.io.uniplugin_webview

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import github.leavesczy.matisse.Matisse
import github.leavesczy.matisse.MatisseContract
import github.leavesczy.matisse.MediaResource

class MatisseProxyFragment : Fragment() {

    interface MatisseResultCallback {
        fun onResult(uris: Array<Uri>?)
    }

    private var mCallback: MatisseResultCallback? = null
    private var matisseConfig: Matisse? = null
    private lateinit var launcher: ActivityResultLauncher<Matisse>
    private var isStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        launcher = registerForActivityResult(MatisseContract()) { result: List<MediaResource>? ->
            Log.d("MatisseProxyFragment", "onResult: ${result?.size}")
            if (result != null && result.isNotEmpty()) {
                val uris = result.map { it.uri }.toTypedArray()
                mCallback?.onResult(uris)
            } else {
                mCallback?.onResult(null)
            }
            mCallback = null
            removeSelf()
        }
        
        matisseConfig?.let {
            if (!isStarted) {
                isStarted = true
                launcher.launch(it)
            }
        }
    }

    fun start(matisse: Matisse, callback: MatisseResultCallback) {
        this.mCallback = callback
        this.matisseConfig = matisse
        
        if (::launcher.isInitialized && !isStarted) {
            isStarted = true
            try {
                launcher.launch(matisse)
            } catch (e: Exception) {
                e.printStackTrace()
                mCallback?.onResult(null)
                removeSelf()
            }
        }
    }

    fun startDefaultMatisse(maxSelectable: Int, callback: MatisseResultCallback) {
        val matisse = Matisse(
            maxSelectable = maxSelectable,
            imageEngine = github.leavesczy.matisse.CoilImageEngine()
            // captureStrategy = null // 默认就是 null，不传即代表不开启拍照
        )
        start(matisse, callback)
    }

    private fun removeSelf() {
        val fm = parentFragmentManager
        fm.beginTransaction().remove(this).commitAllowingStateLoss()
    }
}
