package io.adlogic.sample.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import io.adlogic.sample.data.network.UserListService
import io.adlogic.sample.data.network.model.asDatabaseModel
import io.adlogic.sample.data.database.UsersDatabase
import io.adlogic.sample.data.database.asDomainModel
import io.adlogic.sample.data.domain.UserListItem
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