package com.itg.template.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.itg.template.data.network.UserListService
import com.itg.template.data.network.model.asDatabaseModel
import com.itg.template.data.database.UsersDatabase
import com.itg.template.data.database.asDomainModel
import com.itg.template.data.domain.UserListItem
import timber.log.Timber
import javax.inject.Inject

class UserListRepository @Inject constructor(
    private val userListService: UserListService,
    private val database: UsersDatabase,
) {

    val users: LiveData<List<UserListItem>> =
        database.usersDao.getDatabaseUsers().map {
            it.asDomainModel()
        }

    suspend fun refreshUserList() {
        try {
            val users = userListService.getUserList()
            database.usersDao.insertAll(users.asDatabaseModel())
        } catch (e: Exception) {
            Timber.w(e)
        }
    }
}