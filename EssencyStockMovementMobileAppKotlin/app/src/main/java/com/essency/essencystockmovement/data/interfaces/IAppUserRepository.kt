package com.essency.essencystockmovement.data.interfaces

import com.essency.essencystockmovement.data.model.AppUser

// IAppUserRepository.kt
interface IAppUserRepository {
    fun insert(appUser: AppUser): Long
    fun getAll(): List<AppUser>
    fun getById(id: Int): AppUser?
    fun update(appUser: AppUser): Int
    fun deleteById(id: Int): Int
    // Agrega más métodos según necesites (p.ej. getByUsername, etc.)
}
