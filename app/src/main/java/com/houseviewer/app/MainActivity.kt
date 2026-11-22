package com.houseviewer.app

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.houseviewer.app.gl.HouseGLSurfaceView
import com.houseviewer.app.model.HouseModel
import com.houseviewer.app.model.ModelRepository

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: HouseGLSurfaceView
    private lateinit var tvModelTitle: TextView
    private lateinit var tvModelSubtitle: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnImport: ImageButton
    private lateinit var btnList: ImageButton
    private lateinit var btnMore: ImageButton

    private lateinit var repository: ModelRepository
    private var models: List<HouseModel> = emptyList()
    private var currentIndex: Int = 0

    private val openObjLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            promptNameAndImport(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        tvModelTitle = findViewById(R.id.tvModelTitle)
        tvModelSubtitle = findViewById(R.id.tvModelSubtitle)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnImport = findViewById(R.id.btnImportModel)
        btnList = findViewById(R.id.btnModelList)
        btnMore = findViewById(R.id.btnMore)

        repository = ModelRepository(this)
        models = repository.listModels()

        btnPrev.setOnClickListener {
            if (models.isNotEmpty()) {
                currentIndex = (currentIndex - 1 + models.size) % models.size
                loadCurrentModel()
            }
        }

        btnNext.setOnClickListener {
            if (models.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % models.size
                loadCurrentModel()
            }
        }

        btnImport.setOnClickListener {
            openObjLauncher.launch(arrayOf("*/*"))
        }

        btnList.setOnClickListener {
            showStoredModelsDialog()
        }

        btnMore.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.menu_toggle_bg))
            popup.menu.add(0, 2, 1, getString(R.string.menu_reset_camera))
            val current = models.getOrNull(currentIndex)
            if (current != null && !current.isBuiltIn) {
                popup.menu.add(0, 3, 2, getString(R.string.menu_delete_current))
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        glSurfaceView.toggleBackground()
                        true
                    }
                    2 -> {
                        glSurfaceView.resetCamera()
                        true
                    }
                    3 -> {
                        current?.let { confirmDeleteModel(it) }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        loadCurrentModel()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    private fun loadCurrentModel() {
        if (models.isEmpty()) return
        currentIndex = currentIndex.coerceIn(0, models.lastIndex)
        val model = models[currentIndex]

        runCatching {
            val mesh = repository.loadMesh(model)
            glSurfaceView.displayMesh(mesh)
            tvModelTitle.text = model.displayName
            val badge = if (model.isBuiltIn) "Built-in" else "Stored"
            tvModelSubtitle.text = "${currentIndex + 1} / ${models.size} · $badge · ${mesh.triangleCount} tris"
        }.onFailure { err ->
            Toast.makeText(
                this,
                "Failed to load ${model.displayName}: ${err.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun promptNameAndImport(uri: Uri) {
        val input = EditText(this).apply {
            hint = "House name (optional)"
        }
        AlertDialog.Builder(this)
            .setTitle("Store 3D Model (.obj)")
            .setMessage("Save this Wavefront .obj file to the app's local library:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    val imported = repository.importObjFromUri(uri, input.text?.toString())
                    models = repository.listModels()
                    currentIndex = models.indexOfFirst { it.id == imported.id }.coerceAtLeast(0)
                    glSurfaceView.resetCamera()
                    loadCurrentModel()
                    Toast.makeText(this, "Stored '${imported.displayName}'", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this, "Import failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStoredModelsDialog() {
        val labels = models.mapIndexed { idx, m ->
            val tag = if (m.isBuiltIn) "Built-in" else "Stored"
            "${idx + 1}. ${m.displayName} ($tag)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Stored 3D Houses (${models.size})")
            .setItems(labels) { _, which ->
                currentIndex = which
                loadCurrentModel()
            }
            .setPositiveButton("Import .obj") { _, _ ->
                openObjLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDeleteModel(model: HouseModel) {
        AlertDialog.Builder(this)
            .setTitle("Delete Model")
            .setMessage("Remove '${model.displayName}' from stored models?")
            .setPositiveButton("Delete") { _, _ ->
                repository.deleteCustomModel(model)
                models = repository.listModels()
                currentIndex = currentIndex.coerceIn(0, (models.size - 1).coerceAtLeast(0))
                loadCurrentModel()
                Toast.makeText(this, "Deleted '${model.displayName}'", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
