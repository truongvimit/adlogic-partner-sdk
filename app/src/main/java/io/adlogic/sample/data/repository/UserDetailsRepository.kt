package io.adlogic.sample.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import io.adlogic.sample.data.network.UserDetailsService
import io.adlogic.sample.data.network.model.asDatabaseModel
import io.adlogic.sample.data.database.UsersDatabase
import io.adlogic.sample.data.database.asDomainModel
import io.adlogic.sample.data.domain.UserDetails
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