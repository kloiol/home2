package com.houseviewer.app

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.houseviewer.app.city.CityConfig
import com.houseviewer.app.city.CityGenerator
import com.houseviewer.app.city.GeneratedCityResult
import com.houseviewer.app.city.TensorFieldStyle
import com.houseviewer.app.gl.HouseGLSurfaceView
import com.houseviewer.app.model.HouseModel
import com.houseviewer.app.model.ModelRepository
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: HouseGLSurfaceView
    private lateinit var tvModelTitle: TextView
    private lateinit var tvModelSubtitle: TextView
    private lateinit var btnModeToggle: TextView
    private lateinit var categoryScrollView: HorizontalScrollView
    private lateinit var categoryFilterBar: LinearLayout
    private lateinit var cityControlsBar: LinearLayout
    private lateinit var btnCitySettings: TextView
    private lateinit var btnCityRegenerate: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnImport: ImageButton
    private lateinit var btnList: ImageButton
    private lateinit var btnMore: ImageButton

    private lateinit var repository: ModelRepository
    private val cityExecutor = Executors.newSingleThreadExecutor()

    private var allModels: List<HouseModel> = emptyList()
    private var filteredModels: List<HouseModel> = emptyList()
    private var selectedCategory: String = CATEGORY_ALL
    private var currentIndex: Int = 0

    private var isCityMode: Boolean = false
    private var isGeneratingCity: Boolean = false
    private var cityConfig: CityConfig = CityConfig()
    private var lastCityResult: GeneratedCityResult? = null

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
        btnModeToggle = findViewById(R.id.btnModeToggle)
        categoryScrollView = findViewById(R.id.categoryScrollView)
        categoryFilterBar = findViewById(R.id.categoryFilterBar)
        cityControlsBar = findViewById(R.id.cityControlsBar)
        btnCitySettings = findViewById(R.id.btnCitySettings)
        btnCityRegenerate = findViewById(R.id.btnCityRegenerate)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnImport = findViewById(R.id.btnImportModel)
        btnList = findViewById(R.id.btnModelList)
        btnMore = findViewById(R.id.btnMore)

        repository = ModelRepository(this)
        refreshModelsAndFilters(keepModelId = null)

        btnModeToggle.setOnClickListener {
            toggleAppMode()
        }

        btnCitySettings.setOnClickListener {
            showCitySettingsDialog()
        }

        btnCityRegenerate.setOnClickListener {
            cityConfig = cityConfig.copy(seed = System.currentTimeMillis())
            generateAndShowCity()
        }

        btnPrev.setOnClickListener {
            if (!isCityMode && filteredModels.isNotEmpty()) {
                currentIndex = (currentIndex - 1 + filteredModels.size) % filteredModels.size
                loadCurrentModel()
            }
        }

        btnNext.setOnClickListener {
            if (!isCityMode && filteredModels.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % filteredModels.size
                loadCurrentModel()
            }
        }

        btnImport.setOnClickListener {
            openObjLauncher.launch(arrayOf("*/*"))
        }

        btnList.setOnClickListener {
            if (isCityMode) {
                showCitySettingsDialog()
            } else {
                showStoredModelsDialog()
            }
        }

        btnMore.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.menu_toggle_bg))
            popup.menu.add(0, 2, 1, getString(R.string.menu_reset_camera))
            if (isCityMode) {
                popup.menu.add(0, 4, 2, "City Settings")
            } else {
                val current = filteredModels.getOrNull(currentIndex)
                if (current != null && !current.isBuiltIn) {
                    popup.menu.add(0, 3, 2, getString(R.string.menu_delete_current))
                }
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
                        filteredModels.getOrNull(currentIndex)?.let { confirmDeleteModel(it) }
                        true
                    }
                    4 -> {
                        showCitySettingsDialog()
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

    override fun onDestroy() {
        super.onDestroy()
        cityExecutor.shutdownNow()
    }

    private fun toggleAppMode() {
        isCityMode = !isCityMode
        if (isCityMode) {
            btnModeToggle.text = "Models"
            categoryScrollView.visibility = View.GONE
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
            cityControlsBar.visibility = View.VISIBLE

            val existing = lastCityResult
            if (existing != null) {
                displayCityResult(existing)
            } else {
                generateAndShowCity()
            }
        } else {
            btnModeToggle.text = "City"
            categoryScrollView.visibility = View.VISIBLE
            btnPrev.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            cityControlsBar.visibility = View.GONE
            loadCurrentModel()
        }
    }

    private fun generateAndShowCity() {
        if (isGeneratingCity) return
        isGeneratingCity = true
        val activeConfig = cityConfig
        tvModelTitle.text = "Generating Tensor City..."
        tvModelSubtitle.text = "Tracing smooth roads & placing ${activeConfig.houseCount} houses, ${activeConfig.cathedralCount} cathedrals"
        btnCityRegenerate.isEnabled = false

        cityExecutor.execute {
            val result = runCatching {
                CityGenerator.generate(repository, activeConfig)
            }
            runOnUiThread {
                isGeneratingCity = false
                btnCityRegenerate.isEnabled = true
                result.onSuccess { generated ->
                    lastCityResult = generated
                    if (isCityMode) {
                        displayCityResult(generated)
                    }
                }.onFailure { err ->
                    tvModelTitle.text = "City Generation Failed"
                    tvModelSubtitle.text = err.localizedMessage ?: "Unknown error"
                    Toast.makeText(this, "Error generating city: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayCityResult(result: GeneratedCityResult) {
        glSurfaceView.displayMesh(result.mesh, cityMode = true)
        val presetShort = when (cityConfig.fieldStyle) {
            TensorFieldStyle.VILLAGE_FIELDS -> "Village"
            TensorFieldStyle.RADIAL_OLD_TOWN -> "Radial"
            TensorFieldStyle.GRID_PLAZAS -> "Grid"
            TensorFieldStyle.MIXED_ORGANIC -> "Mixed"
            TensorFieldStyle.SOVIET_MICRORAYON -> "Soviet"
        }
        tvModelTitle.text = "Tensor City ($presetShort)"
        tvModelSubtitle.text = if (result.isSovietMode) {
            "${result.placedHouses} blocks · ${result.placedLamps} lamps · ${result.mesh.triangleCount} tris"
        } else {
            "${result.placedHouses} houses · ${result.placedCathedrals} cathedrals · ${result.placedLamps} lamps · ${result.mesh.triangleCount} tris"
        }
    }

    private fun showCitySettingsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(12), pad, dp(8))
        }
        scroll.addView(container)

        // 1. City Size (30 .. 110)
        var sizeVal = cityConfig.citySize.toInt().coerceIn(30, 110)
        val tvSize = makeLabel("City Size: $sizeVal x $sizeVal")
        val sbSize = SeekBar(this).apply {
            max = 80
            progress = sizeVal - 30
            setOnSeekBarChangeListener(simpleSeekListener { p ->
                sizeVal = p + 30
                tvSize.text = "City Size: $sizeVal x $sizeVal"
            })
        }
        container.addView(tvSize)
        container.addView(sbSize)

        // 2. Number of Houses (10 .. 160)
        var housesVal = cityConfig.houseCount.coerceIn(10, 160)
        val tvHouses = makeLabel("Buildings (Houses): $housesVal")
        val sbHouses = SeekBar(this).apply {
            max = 150
            progress = housesVal - 10
            setOnSeekBarChangeListener(simpleSeekListener { p ->
                housesVal = p + 10
                tvHouses.text = "Buildings (Houses): $housesVal"
            })
        }
        container.addView(tvHouses)
        container.addView(sbHouses)

        // 3. Number of Cathedrals (0 .. 6)
        var cathVal = cityConfig.cathedralCount.coerceIn(0, 6)
        val tvCath = makeLabel("Gothic Cathedrals: $cathVal")
        val sbCath = SeekBar(this).apply {
            max = 6
            progress = cathVal
            setOnSeekBarChangeListener(simpleSeekListener { p ->
                cathVal = p
                tvCath.text = "Gothic Cathedrals: $cathVal"
            })
        }
        container.addView(tvCath)
        container.addView(sbCath)

        // 4. Road Spacing (5 .. 13)
        var spacingVal = cityConfig.roadSpacing.toInt().coerceIn(5, 13)
        val tvSpacing = makeLabel("Road Spacing: $spacingVal units")
        val sbSpacing = SeekBar(this).apply {
            max = 8
            progress = spacingVal - 5
            setOnSeekBarChangeListener(simpleSeekListener { p ->
                spacingVal = p + 5
                tvSpacing.text = "Road Spacing: $spacingVal units"
            })
        }
        container.addView(tvSpacing)
        container.addView(sbSpacing)

        // 5. Tensor Field Layout Preset (includes new Rural Village & Fields)
        container.addView(makeLabel("Layout Preset:"))
        val styles = TensorFieldStyle.values()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                styles.map { it.label }
            )
            setSelection(styles.indexOf(cityConfig.fieldStyle).coerceAtLeast(0))
        }
        container.addView(spinner)

        // 6. Fenced Agricultural Fields Toggle
        val cbFields = CheckBox(this).apply {
            text = "Include fenced agricultural fields"
            isChecked = cityConfig.includeFields
            setPadding(0, dp(10), 0, dp(4))
        }
        container.addView(cbFields)

        // 7. Optional Seed
        container.addView(makeLabel("Seed (leave blank for random):"))
        val etSeed = EditText(this).apply {
            hint = "Random seed"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(etSeed)

        AlertDialog.Builder(this)
            .setTitle("Procedural City Settings")
            .setView(scroll)
            .setPositiveButton("Generate City") { _, _ ->
                val seedInput = etSeed.text?.toString()?.trim()?.toLongOrNull()
                    ?: System.currentTimeMillis()
                cityConfig = CityConfig(
                    citySize = sizeVal.toFloat(),
                    houseCount = housesVal,
                    cathedralCount = cathVal,
                    fieldStyle = styles[spinner.selectedItemPosition],
                    roadSpacing = spacingVal.toFloat(),
                    includeFields = cbFields.isChecked,
                    seed = seedInput
                )
                if (!isCityMode) {
                    isCityMode = true
                    btnModeToggle.text = "Models"
                    categoryScrollView.visibility = View.GONE
                    btnPrev.visibility = View.GONE
                    btnNext.visibility = View.GONE
                    cityControlsBar.visibility = View.VISIBLE
                }
                generateAndShowCity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun simpleSeekListener(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    private fun refreshModelsAndFilters(keepModelId: String?) {
        allModels = repository.listModels()
        val categories = linkedSetOf(CATEGORY_ALL, "house", "cathedral", "khrushchevka", "lamp_post")
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
        if (!isCityMode) {
            loadCurrentModel()
        }
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
            glSurfaceView.displayMesh(mesh, cityMode = false)
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
                    if (isCityMode) toggleAppMode()
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
            "khrushchevka" -> "Khrushchevka"
            "lamp_post" -> "Lamp Post"
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
