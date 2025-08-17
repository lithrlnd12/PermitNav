package com.permitnav.data.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.permitnav.data.models.TruckDimensions
import com.permitnav.data.models.VehicleInfo
import java.util.Date

class Converters {
    private val gson = Gson()
    
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }
    
    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
    
    @TypeConverter
    fun fromStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType)
    }
    
    @TypeConverter
    fun fromListString(list: List<String>): String {
        return gson.toJson(list)
    }
    
    @TypeConverter
    fun fromVehicleInfo(vehicleInfo: VehicleInfo): String {
        return gson.toJson(vehicleInfo)
    }
    
    @TypeConverter
    fun toVehicleInfo(value: String): VehicleInfo {
        return gson.fromJson(value, VehicleInfo::class.java)
    }
    
    @TypeConverter
    fun fromTruckDimensions(dimensions: TruckDimensions): String {
        return gson.toJson(dimensions)
    }
    
    @TypeConverter
    fun toTruckDimensions(value: String): TruckDimensions {
        return gson.fromJson(value, TruckDimensions::class.java)
    }
}