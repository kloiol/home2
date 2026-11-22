package com.houseviewer.app

import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.houseviewer.app.gl.HouseGLSurfaceView
import com.houseviewer.app.model.HouseModel
import com.houseviewer.app.model.ModelRepository

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: HouseGLSurfaceView
    private lateinit var tvModelTitle: TextView
    private lateinit var tvModelSubtitle: TextView
    private lateinit var categoryFilterBar: LinearLayout
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnImport: ImageButton
    private lateinit var btnList: ImageButton
    private lateinit var btnMore: ImageButton

    private lateinit var repository: ModelRepository
    private var allModels: List<HouseModel> = emptyList()
    private var filteredModels: List<HouseModel> = emptyList()
    private var selectedCategory: String = CATEGORY_ALL
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
        categoryFilterBar = findViewById(R.id.categoryFilterBar)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnImport = findViewById(R.id.btnImportModel)
        btnList = findViewById(R.id.btnModelList)
        btnMore = findViewById(R.id.btnMore)

        repository = ModelRepository(this)
        refreshModelsAndFilters(keepModelId = null)

        btnPrev.setOnClickListener {
            if (filteredModels.isNotEmpty()) {
                currentIndex = (currentIndex - 1 + filteredModels.size) % filteredModels.size
                loadCurrentModel()
            }
        }

        btnNext.setOnClickListener {
            if (filteredModels.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % filteredModels.size
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
            val current = filteredModels.getOrNull(currentIndex)
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
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    private fun refreshModelsAndFilters(keepModelId: String?) {
        allModels = repository.listModels()
        val categories = linkedSetOf(CATEGORY_ALL, "house", "cathedral")
        for (m in allModels) {
            categories.add(m.category)
        }
        if (selectedCategory !in categories) {
            selectedCategory = CATEGORY_ALL
        }
        buildCategoryFilterChips(categories.toList())
        applyCategoryFilter(selectedCategory, keepModelId)
    }

    private fun buildCategoryFilterChips(categories: List<String>) {
        categoryFilterBar.removeAllViews()
        val marginEnd = dp(8)
        val padH = dp(14)
        val padV = dp(6)

        for (cat in categories) {
            val count = if (cat == CATEGORY_ALL) {
                allModels.size
            } else {
                allModels.count { it.category == cat }
            }
            if (cat != CATEGORY_ALL && count == 0) continue

            val label = "${formatCategoryLabel(cat)} ($count)"
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(padH, padV, padH, padV)
                val isSelected = cat == selectedCategory
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_filter_selected else R.drawable.bg_pill_dark
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isSelected) R.color.bg_dark else R.color.text_primary
                    )
                )
                setOnClickListener {
                    if (selectedCategory != cat) {
                        selectedCategory = cat
                        buildCategoryFilterChips(categories)
                        applyCategoryFilter(cat, keepModelId = null)
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.marginEnd = marginEnd
            }
            categoryFilterBar.addView(chip, lp)
        }
    }

    private fun applyCategoryFilter(category: String, keepModelId: String?) {
        filteredModels = if (category == CATEGORY_ALL) {
            allModels
        } else {
            allModels.filter { it.category == category }
        }
        currentIndex = if (keepModelId != null) {
            filteredModels.indexOfFirst { it.id == keepModelId }.coerceAtLeast(0)
        } else {
            0
        }
        loadCurrentModel()
    }

    private fun loadCurrentModel() {
        if (filteredModels.isEmpty()) {
            tvModelTitle.text = "No models"
            tvModelSubtitle.text = "0 / 0"
            return
        }
        currentIndex = currentIndex.coerceIn(0, filteredModels.lastIndex)
        val model = filteredModels[currentIndex]

        runCatching {
            val mesh = repository.loadMesh(model)
            glSurfaceView.displayMesh(mesh)
            tvModelTitle.text = model.displayName
            val catLabel = formatCategoryLabel(model.category)
            tvModelSubtitle.text = "${currentIndex + 1} / ${filteredModels.size} · $catLabel · ${mesh.triangleCount} tris"
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
            hint = "Model name (optional)"
        }
        AlertDialog.Builder(this)
            .setTitle("Store 3D Model (.obj)")
            .setMessage("Save this Wavefront .obj file to the app's local library:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    val imported = repository.importObjFromUri(uri, input.text?.toString())
                    selectedCategory = CATEGORY_ALL
                    refreshModelsAndFilters(keepModelId = imported.id)
                    Toast.makeText(this, "Stored '${imported.displayName}'", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this, "Import failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStoredModelsDialog() {
        val labels = filteredModels.mapIndexed { idx, m ->
            "${idx + 1}. ${m.displayName} [${formatCategoryLabel(m.category)}]"
        }.toTypedArray()

        val titlePrefix = if (selectedCategory == CATEGORY_ALL) {
            "All 3D Models"
        } else {
            "${formatCategoryLabel(selectedCategory)} Models"
        }

        AlertDialog.Builder(this)
            .setTitle("$titlePrefix (${filteredModels.size})")
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
                refreshModelsAndFilters(keepModelId = null)
                Toast.makeText(this, "Deleted '${model.displayName}'", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatCategoryLabel(category: String): String {
        return when (category.lowercase()) {
            CATEGORY_ALL -> "All"
            "house" -> "House"
            "cathedral" -> "Cathedral"
            "custom" -> "Custom"
            else -> category.replaceFirstChar { it.uppercase() }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val CATEGORY_ALL = "all"
    }
}
