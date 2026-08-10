package com.itg.template.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.itg.template.data.network.UserDetailsService
import com.itg.template.data.network.model.asDatabaseModel
import com.itg.template.data.database.UsersDatabase
import com.itg.template.data.database.asDomainModel
import com.itg.template.data.domain.UserDetails
import timber.log.Timber
import javax.inject.Inject

class UserDetailsRepository @Inject constructor(
    private val userDetailsService: UserDetailsService,
    private val database: UsersDatabase
) {

    fun getUserDetails(user: String): LiveData<UserDetails> {
        return database.usersDao.getUserDetails(user).map {
            it.asDomainModel()
        }
    }


    suspend fun refreshUserDetails(user: String) {
        try {
            val userDetails = userDetailsService.getUserDetails(user)
            database.usersDao.insertUserDetails(userDetails.asDatabaseModel())
        } catch (e: Exception) {
            Timber.w(e)
        }
    }

}