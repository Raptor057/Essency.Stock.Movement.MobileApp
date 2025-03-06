package com.essency.essencystockmovement.data.UI.Home.ui.receiving

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.essency.essencystockmovement.data.UI.BaseFragment
import com.essency.essencystockmovement.data.UtilClass.BarcodeParser
import com.essency.essencystockmovement.data.local.MyDatabaseHelper
import com.essency.essencystockmovement.data.model.BarcodeData
import com.essency.essencystockmovement.data.model.StockList
import com.essency.essencystockmovement.data.repository.MovementTypeRepository
import com.essency.essencystockmovement.data.repository.TraceabilityStockListRepository
import com.essency.essencystockmovement.databinding.FragmentStockListBinding
import java.text.SimpleDateFormat
import java.util.Date

class ReceivingFragment : BaseFragment() {

    private var _binding: FragmentStockListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ReceivingAdapter
    private val stockList = mutableListOf<StockList>()
    private lateinit var barcodeParser: BarcodeParser
    private lateinit var repository: TraceabilityStockListRepository
    private lateinit var movementType: MovementTypeRepository
    private lateinit var sharedPreferences: SharedPreferences
    private var moduleName = "RECEIVING"


    private lateinit var dbHelper: MyDatabaseHelper

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        barcodeParser = BarcodeParser()
        _binding = FragmentStockListBinding.inflate(inflater, container, false)
        dbHelper = MyDatabaseHelper(requireContext())
        repository = TraceabilityStockListRepository(MyDatabaseHelper(requireContext()))
        sharedPreferences = requireContext().getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        movementType = MovementTypeRepository(MyDatabaseHelper(requireContext())) //Agregado para obtener el destino segun el modulo

        // 🔹 Inicializa el adapter antes de usar `stockList`
        setupRecyclerView()

        // 🔹 Ahora carga los datos en `stockList`
        stockList.addAll(getStockListForLastTraceability())
        adapter.notifyDataSetChanged()

        binding.editTextNewStockItem.setBackgroundColor(Color.WHITE)
        binding.editTextNewStockItem.requestFocus()
        setupTextInputValidation()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Al volver de otra pantalla, recupera el foco
        binding.editTextNewStockItem.requestFocus()
    }


    private fun getLastInserted(): StockList? {
        val db = dbHelper.readableDatabase
        var lastStock: StockList? = null
        val query = "SELECT * FROM StockList ORDER BY ID DESC LIMIT 1"
        val cursor = db.rawQuery(query, null)

        cursor.use {
            if (it.moveToFirst()) {
                lastStock = cursorToStock(it)
            }
        }

        return lastStock
    }

    private fun setupTextInputValidation() {
        binding.editTextNewStockItem.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, event ->
            // Detectar "Enter" desde el teclado físico o botón en pantalla
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)) {

                // Primero, obtener el registro de trazabilidad actual y validar que se hayan completado los campos de Datos de Recepción
                val currentTraceability = repository.getLastInserted()
                if (currentTraceability == null || currentTraceability.batchNumber.isEmpty() || currentTraceability.numberOfHeaters == 0) {
                    Toast.makeText(requireContext(), "Por favor, complete los campos en Datos de Recepción.", Toast.LENGTH_SHORT).show()
                    return@OnEditorActionListener true
                }

                val input = binding.editTextNewStockItem.text.toString().trim()
                if (input.isNotEmpty()) {
                    val parsedData = barcodeParser.parseBarcode(input)

                    if (parsedData != null) {

                        val scannedSerial = parsedData.serialNumberWH1 ?: parsedData.serialNumberWH2 ?: parsedData.serialNumber
                        val duplicate = stockList.any { it.serialNumber == scannedSerial }
                        if (duplicate) {
                            binding.editTextNewStockItem.error = "Este dato ya fue escaneado"
                            return@OnEditorActionListener true // Evita la inserción
                        }

                        val stockItems = convertToStockList(parsedData)

                        for (item in stockItems) {
                            val insertedId = insertNewStockItem(item)
                            if (insertedId != -1L) {
                                // Copiar el item con el nuevo ID
                                val itemWithId = item.copy(id = insertedId.toInt())
                                stockList.add(itemWithId)
                                //adapter.notifyDataSetChanged()
                                adapter.notifyItemInserted(stockList.size - 1)
                            } else {
                                binding.editTextNewStockItem.error = "Error inserting item"
                            }
                        }
                        adapter.notifyDataSetChanged()
                        // Después de insertar los ítems y limpiar el campo...
                        binding.editTextNewStockItem.text.clear()

                        // Verificar si se completó el lote actual
                        val lastTraceability = repository.getLastInserted()
                        if (lastTraceability != null) {
                            // Obtenemos la cantidad total de calentadores escaneados para este registro
                            val scannedItems = getStockListForLastTraceability()
                            val scannedCount = scannedItems.size

                            if (scannedCount >= lastTraceability.numberOfHeaters) {
                                // Si se han escaneado la cantidad esperada, marcamos el registro como finalizado
                                val updatedTraceability = lastTraceability.copy(finish = true)
                                repository.update(updatedTraceability)
                                Toast.makeText(requireContext(), "Lote completado. Iniciando nuevo registro.", Toast.LENGTH_SHORT).show()

                                // Limpiar la lista para iniciar un nuevo lote
                                stockList.clear()
                                adapter.notifyDataSetChanged()
                            } else {
                                // Si aún no se ha completado el lote, actualizamos el contador según la cantidad total escaneada
                                val updatedTraceability = lastTraceability.copy(numberOfHeatersFinished = scannedCount)
                                repository.update(updatedTraceability)
                                Toast.makeText(requireContext(), "Calentador agregado: $scannedCount de ${lastTraceability.numberOfHeaters}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        binding.editTextNewStockItem.error = "Invalid barcode format!"
                    }
                }
                return@OnEditorActionListener true // Indicar que el evento fue manejado
            }
            false
        })
    }

    private fun insertNewStockItem(stockItem: StockList): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("IDTraceabilityStockList", stockItem.idTraceabilityStockList)
            put("Company", stockItem.company)
            put("Source", stockItem.source)
            put("SourceLoc", stockItem.sourceLoc)
            put("Destination", stockItem.destination)
            put("DestinationLoc", stockItem.destinationLoc)
            put("Pallet", stockItem.pallet)
            put("PartNo", stockItem.partNo)
            put("Rev", stockItem.rev)
            put("Lot", stockItem.lot)
            put("Qty", stockItem.qty)
            put("ProductionDate", stockItem.productionDate)
            put("CountryOfProduction", stockItem.countryOfProduction)
            put("SerialNumber", stockItem.serialNumber)
            put("Date", stockItem.date)
            put("TimeStamp", stockItem.timeStamp)
            put("User", stockItem.user)
            put("ContBolNum", stockItem.contBolNum)
        }

        val insertedId = db.insert("StockList", null, values)
        db.close()
        return insertedId
    }

    private fun getStockListForLastTraceability(): List<StockList> {
        val db = dbHelper.readableDatabase
        val stockList = mutableListOf<StockList>()

        // 🔹 Obtener el último `IDTraceabilityStockList`
        val lastTraceabilityStock = repository.getLastInserted()
        val traceabilityId = lastTraceabilityStock?.id ?: return emptyList() // Si no hay ID, retorna lista vacía

        //val query = "SELECT * FROM StockList WHERE IDTraceabilityStockList = ? ORDER BY ID DESC"
        val query = "SELECT SL.* FROM StockList SL INNER JOIN TraceabilityStockList TSL ON SL.IDTraceabilityStockList = TSL.ID WHERE SL.IDTraceabilityStockList = ? ORDER BY SL.ID DESC"
        val cursor = db.rawQuery(query, arrayOf(traceabilityId.toString()))

        cursor.use {
            while (it.moveToNext()) {
                stockList.add(cursorToStock(it))
            }
        }

        return stockList
    }

    private fun convertToStockList(parsedData: BarcodeData): List<StockList> {
        var destination = movementType.getDestinationInMovementTypesByTypeandUserType(moduleName, sharedPreferences.getString("userType", "Unknown") ?: "Unknown")
        val lastTraceability = repository.getLastInserted()
        val traceId = lastTraceability?.id ?: 0
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        val currentDate = sdf.format(Date())

        // Función para crear un StockList individual según los parámetros
        @RequiresApi(Build.VERSION_CODES.O)

        fun buildStock(
            partNumber: String?,
            rev: String?,
            count: Int?,
            pallet: String?,
            productionDate: String?,
            country: String?,
            serial: String?
        ): StockList {
            return StockList(
                id = 0,
                idTraceabilityStockList = traceId,
                company = country ?: "001",  // 🔹 Si es null, usar "001"
                source = sharedPreferences.getString("userType", "Unknown") ?: "Unknown",//lastTraceability?.source ?: "Unknown",
                //sourceLoc = lastTraceability?.sourceLoc ?: "N/A", // 🔹 Evitar valores vacíos
                sourceLoc = "Unknown Source", // 🔹 Evitar valores vacíos
                destination = destination,//lastTraceability?.destination ?: "Unknown Destination",
                //destinationLoc = lastTraceability?.destinationLoc ?: "N/A",
                destinationLoc = "Unknown Destination",
                pallet = pallet ?: "N/A",
                partNo = partNumber ?: "Unknown",
                rev = rev ?: "N/A",
                lot = lastTraceability?.batchNumber ?: "N/A",
                qty = count ?: 1,
                productionDate = productionDate ?: "N/A",
                countryOfProduction = country ?: "Unknown",
                serialNumber = serial ?: "N/A",
                date = currentDate.toString(),
                timeStamp = currentDate.toString(),
                user = sharedPreferences.getString("userName", "Unknown") ?: "Unknown",
                contBolNum = "${lastTraceability?.batchNumber ?: "N/A"} "
            )
        }

        return when {
            // Caso 1: Dos calentadores (pallet + partNumberWH2 != null)
            parsedData.pallet != null && parsedData.partNumberWH2 != null -> {
                val item1 = buildStock(
                    parsedData.partNumberWH1,
                    parsedData.revWH1,
                    parsedData.countOfTradeItemsWH1,
                    parsedData.pallet,
                    parsedData.productionDateWH1,
                    parsedData.countryOfProductionWH1,
                    parsedData.serialNumberWH1
                )
                val item2 = buildStock(
                    parsedData.partNumberWH2,
                    parsedData.revWH2,
                    parsedData.countOfTradeItemsWH2,
                    parsedData.pallet,
                    parsedData.productionDateWH2,
                    parsedData.countryOfProductionWH2,
                    parsedData.serialNumberWH2
                )
                listOf(item1, item2)
            }

            // Caso 2: un solo calentador sin pallet (pallet == null), usando “partNumber” normal
            parsedData.pallet == null && parsedData.partNumber != null -> {
                val singleItem = buildStock(
                    parsedData.partNumber,
                    parsedData.rev,
                    parsedData.countOfTradeItems,
                    null,
                    parsedData.productionDate,
                    parsedData.countryOfProduction,
                    parsedData.serialNumber
                )
                listOf(singleItem)
            }

            // Caso 3: un solo calentador con pallet pero sin partNumberWH2 (ej. un “WH1”)
            parsedData.pallet != null && parsedData.partNumberWH2 == null -> {
                val singleItem = buildStock(
                    parsedData.partNumberWH1,
                    parsedData.revWH1,
                    parsedData.countOfTradeItemsWH1,
                    parsedData.pallet,
                    parsedData.productionDateWH1,
                    parsedData.countryOfProductionWH1,
                    parsedData.serialNumberWH1
                )
                listOf(singleItem)
            }

            // Caso 4: otra variante o error
            else -> emptyList() // Devuelve lista vacía si no hay datos
        }
    }



    private fun setupRecyclerView() {
        adapter = ReceivingAdapter(stockList) { itemToDelete ->
            removeStockItem(itemToDelete)  // <- Aquí definimos la acción de borrar
        }
        binding.recyclerViewStockList.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewStockList.adapter = adapter
    }


    private fun removeStockItem(item: StockList) {
        // Eliminar de la base de datos
        val rowsDeleted = dbHelper.writableDatabase.delete(
            "StockList",
            "ID = ?",
            arrayOf(item.id.toString())
        )

        if (rowsDeleted > 0) {
            // Remover el ítem de la lista en memoria y notificar al adapter
            stockList.remove(item)
            adapter.notifyDataSetChanged()

            // Actualizar el registro de trazabilidad según la nueva cantidad de piezas escaneadas
            val lastTraceability = repository.getLastInserted()
            if (lastTraceability != null) {
                val scannedItems = getStockListForLastTraceability()
                val scannedCount = scannedItems.size

                // Si se elimina una pieza y el total es menor que el esperado, se desmarca la finalización.
                val updatedTraceability = if (scannedCount < lastTraceability.numberOfHeaters) {
                    lastTraceability.copy(
                        finish = false,
                        numberOfHeatersFinished = scannedCount
                    )
                } else {
                    // Si aún se cumple o se supera, actualizamos solo el contador.
                    lastTraceability.copy(numberOfHeatersFinished = scannedCount)
                }
                repository.update(updatedTraceability)
            }
        } else {
            // Mostrar un mensaje de error si no se pudo borrar
            Toast.makeText(requireContext(), "Error al borrar el ítem", Toast.LENGTH_SHORT).show()
        }
    }

//private fun removeStockItem(item: StockList) {
//    // 1) Eliminar de la BD usando tu repositorio
//    val rowsDeleted = dbHelper.writableDatabase.delete(
//        "StockList",
//        "ID = ?",
//        arrayOf(item.id.toString())
//    )
//
//    // 2) Si rowsDeleted > 0, lo quitas de la lista en memoria
//    if (rowsDeleted > 0) {
//        stockList.remove(item)
//        adapter.notifyDataSetChanged()
//    } else {
//        // Muestra un error o algo si no se pudo borrar
//    }
//}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun cursorToStock(cursor: Cursor): StockList {
        return StockList(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("ID")),
            idTraceabilityStockList = cursor.getInt(cursor.getColumnIndexOrThrow("IDTraceabilityStockList")),
            company = cursor.getString(cursor.getColumnIndexOrThrow("Company")),
            source = cursor.getString(cursor.getColumnIndexOrThrow("Source")),
            sourceLoc = cursor.getColumnIndex("SourceLoc").takeIf { it != -1 }?.let { cursor.getString(it) },
            destination = cursor.getString(cursor.getColumnIndexOrThrow("Destination")),
            destinationLoc = cursor.getColumnIndex("DestinationLoc").takeIf { it != -1 }?.let { cursor.getString(it) },
            pallet = cursor.getColumnIndex("Pallet").takeIf { it != -1 }?.let { cursor.getString(it) },
            partNo = cursor.getString(cursor.getColumnIndexOrThrow("PartNo")),
            rev = cursor.getString(cursor.getColumnIndexOrThrow("Rev")),
            lot = cursor.getString(cursor.getColumnIndexOrThrow("Lot")),
            qty = cursor.getInt(cursor.getColumnIndexOrThrow("Qty")),
            productionDate = cursor.getColumnIndex("ProductionDate").takeIf { it != -1 }?.let { cursor.getString(it) },
            countryOfProduction = cursor.getColumnIndex("CountryOfProduction").takeIf { it != -1 }?.let { cursor.getString(it) },
            serialNumber = cursor.getColumnIndex("SerialNumber").takeIf { it != -1 }?.let { cursor.getString(it) },
            date = cursor.getString(cursor.getColumnIndexOrThrow("Date")),
            timeStamp = cursor.getString(cursor.getColumnIndexOrThrow("TimeStamp")),
            user = cursor.getString(cursor.getColumnIndexOrThrow("User")),
            contBolNum = cursor.getString(cursor.getColumnIndexOrThrow("ContBolNum"))
        )
    }
}
