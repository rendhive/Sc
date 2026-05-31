package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.model.RawLead
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExcelExportHelper {

    fun exportLeadsToDownloads(
        context: Context,
        category: String,
        region: String,
        premiumLeads: List<RawLead>
    ): String? {
        val tab1Leads = premiumLeads.filter { it.rating != null && it.rating > 0f }
        val tab2Leads = premiumLeads.filter { it.rating == null || it.rating == 0f }

        val workbook = XSSFWorkbook()

        val headerFont = workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
            fontHeightInPoints = 11.toShort()
        }

        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFont(headerFont)
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val dataStyle = workbook.createCellStyle().apply {
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val columns = listOf(
            "No", "Place ID", "Nama Bisnis", "Nomor Telepon", 
            "Alamat Lengkap", "Rating", "Memiliki Foto", "Kategori", "Wilayah", "Tanggal Scan"
        )

        // --- TAB 1: PROSPEK PREMIUM ---
        val sheet1 = workbook.createSheet("Prospek Premium")
        val headerRow1 = sheet1.createRow(0)
        for (i in columns.indices) {
            val cell = headerRow1.createCell(i)
            cell.setCellValue(columns[i])
            cell.cellStyle = headerStyle
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        for (idx in tab1Leads.indices) {
            val lead = tab1Leads[idx]
            val row = sheet1.createRow(idx + 1)

            row.createCell(0).setCellValue((idx + 1).toDouble())
            row.createCell(1).setCellValue(lead.placeId)
            row.createCell(2).setCellValue(lead.name)
            row.createCell(3).setCellValue(lead.phone ?: "-")
            row.createCell(4).setCellValue(lead.address ?: "-")
            row.createCell(5).setCellValue(lead.rating?.toDouble() ?: 0.0)
            row.createCell(6).setCellValue(if (lead.hasPhotos) "Ya" else "Tidak")
            row.createCell(7).setCellValue(lead.category)
            row.createCell(8).setCellValue(lead.region)
            row.createCell(9).setCellValue(dateFormat.format(Date(lead.timestamp)))

            for (i in columns.indices) {
                row.getCell(i).cellStyle = dataStyle
            }
        }

        try {
            for (i in columns.indices) {
                sheet1.autoSizeColumn(i)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --- TAB 2: PROSPEK BISNIS BARU ---
        val sheet2 = workbook.createSheet("Prospek Bisnis Baru")
        val headerRow2 = sheet2.createRow(0)
        for (i in columns.indices) {
            val cell = headerRow2.createCell(i)
            cell.setCellValue(columns[i])
            cell.cellStyle = headerStyle
        }

        for (idx in tab2Leads.indices) {
            val lead = tab2Leads[idx]
            val row = sheet2.createRow(idx + 1)

            row.createCell(0).setCellValue((idx + 1).toDouble())
            row.createCell(1).setCellValue(lead.placeId)
            row.createCell(2).setCellValue(lead.name)
            row.createCell(3).setCellValue(lead.phone ?: "-")
            row.createCell(4).setCellValue(lead.address ?: "-")
            row.createCell(5).setCellValue("Null/Belum Ada")
            row.createCell(6).setCellValue(if (lead.hasPhotos) "Ya" else "Tidak")
            row.createCell(7).setCellValue(lead.category)
            row.createCell(8).setCellValue(lead.region)
            row.createCell(9).setCellValue(dateFormat.format(Date(lead.timestamp)))

            for (i in columns.indices) {
                row.getCell(i).cellStyle = dataStyle
            }
        }

        try {
            for (i in columns.indices) {
                sheet2.autoSizeColumn(i)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --- FILE SAVING TO DOWNLOAD/B2B_LEADS ---
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanCat = category.replace(Regex("[^a-zA-Z0-9]"), "_")
        val cleanRegion = region.replace(Regex("[^a-zA-Z0-9]"), "_")
        val fileName = "Leads_${cleanCat}_${cleanRegion}_$timestamp.xlsx"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Write to system Downloads using MediaStore
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/B2B_Leads")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri).use { outputStream ->
                        if (outputStream != null) {
                            workbook.write(outputStream)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    workbook.close()
                    "/Download/B2B_Leads/$fileName"
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    workbook.close()
                    e.printStackTrace()
                    null
                }
            } else {
                workbook.close()
                null
            }
        } else {
            // Fallback for older devices/simulators (write to direct file storage)
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadDir, "B2B_Leads")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)
            try {
                FileOutputStream(targetFile).use { out ->
                    workbook.write(out)
                }
                workbook.close()
                targetFile.absolutePath
            } catch (e: Exception) {
                workbook.close()
                e.printStackTrace()
                null
            }
        }
    }
}
