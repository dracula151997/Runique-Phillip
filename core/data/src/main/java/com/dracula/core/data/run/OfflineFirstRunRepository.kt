package com.dracula.core.data.run

import com.dracula.core.data.auth.EncryptedSessionStorage
import com.dracula.core.database.dao.RunPendingSyncDao
import com.dracula.core.database.mappers.toRun
import com.dracula.core.domain.SyncRunScheduler
import com.dracula.core.domain.run.LocalRunDataSource
import com.dracula.core.domain.run.RemoteRunDataSource
import com.dracula.core.domain.run.Run
import com.dracula.core.domain.run.RunId
import com.dracula.core.domain.run.RunRepository
import com.dracula.core.domain.utils.DataError
import com.dracula.core.domain.utils.EmptyResult
import com.dracula.core.domain.utils.Result
import com.dracula.core.domain.utils.asEmptyDataResult
import com.dracula.core.networking.get
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineFirstRunRepository(
	private val localRunDataSource: LocalRunDataSource,
	private val remoteRunDataSource: RemoteRunDataSource,
	private val applicationScope: CoroutineScope,
	private val runPendingSyncDao: RunPendingSyncDao,
	private val sessionStorage: EncryptedSessionStorage,
	private val syncRunScheduler: SyncRunScheduler,
	private val client: HttpClient,
) : RunRepository {
	override fun getRuns(): Flow<List<Run>> {
		return localRunDataSource.getRuns()
	}

	override suspend fun fetchRuns(): EmptyResult<DataError> {
		return when (val result = remoteRunDataSource.getRuns()) {
			is Result.Success -> {
				applicationScope.async {
					localRunDataSource.upsertRuns(result.data).asEmptyDataResult()
				}.await()
			}

			is Result.Error -> result.asEmptyDataResult()
		}
	}

	override suspend fun upsertRun(run: Run, mapPicture: ByteArray): EmptyResult<DataError> {
		val localResult = localRunDataSource.upsertRun(run)
		if (localResult !is Result.Success) {
			return localResult.asEmptyDataResult()
		}
		val runId = run.copy(id = localResult.data)
		return when (val remoteResult =
			remoteRunDataSource.postRun(run = runId, mapPicture = mapPicture)) {
			is Result.Success -> {
				applicationScope.async {
					localRunDataSource.upsertRun(remoteResult.data).asEmptyDataResult()
				}.await()
			}

			is Result.Error -> {
				applicationScope.launch {
					syncRunScheduler.scheduleSync(
						SyncRunScheduler.SyncType.CreateRun(
							run = run,
							mapPictureBytes = mapPicture
						)
					)
				}.join()
				Result.Success(Unit)
			}
		}
	}

	override suspend fun deleteRun(id: RunId) {
		val isPendingSync = runPendingSyncDao.getDeletedRunSyncEntity(runId = id) != null
		/**
		 * Edge case where the run was created and deleted before it was synced (in offline mode)
		 * In this case, we don't need to delete the run from the remote server
		 */
		if (isPendingSync) {
			runPendingSyncDao.deleteDeletedRunSyncEntity(runId = id)
			return
		}
		localRunDataSource.deleteRun(id)
		val remoteResult = applicationScope.async {
			remoteRunDataSource.deleteRun(id)
		}.await()
		if (remoteResult is Result.Error) {
			applicationScope.launch {
				syncRunScheduler.scheduleSync(SyncRunScheduler.SyncType.DeleteRun(runId = id))
			}
		}

	}

	override suspend fun syncPendingRuns() {
		withContext(Dispatchers.IO) {
			val userId = sessionStorage.get()?.userId ?: return@withContext

			val createdRuns = async {
				runPendingSyncDao.getAllRunPendingSyncEntities(userId = userId)
			}
			val deletedRuns = async {
				runPendingSyncDao.getAllDeletedRunSyncEntities(userId = userId)
			}

			val createdJobs = createdRuns
				.await()
				.map {
					launch {
						val run = it.run.toRun()
						when (val result = remoteRunDataSource.postRun(run, it.mapPicture)) {
							is Result.Success -> {
								applicationScope.launch {
									runPendingSyncDao.deleteRunPendingSyncEntity(it.runId)
								}.join()
							}

							is Result.Error -> Unit
						}
					}
				}

			val deletedJobs = deletedRuns
				.await()
				.map {
					launch {
						val runId = it.runId
						when (val result = remoteRunDataSource.deleteRun(runId)) {
							is Result.Success -> {
								applicationScope.launch {
									runPendingSyncDao.deleteDeletedRunSyncEntity(it.runId)
								}.join()
							}

							is Result.Error -> Unit
						}
					}
				}
			// This ensures that the main coroutine waits for all the launched jobs
			// (both created and deleted runs) to finish before proceeding
			createdJobs.forEach { it.join() }
			deletedJobs.forEach { it.join() }
		}
	}

	override suspend fun logout(): EmptyResult<DataError.Network> {
		val result = client.get<Unit>(
			route = "/logout"
		).asEmptyDataResult()
		client.plugin(Auth)
			.providers
			.filterIsInstance<BearerAuthProvider>()
			.firstOrNull()
			?.clearToken()
		return result
	}

	override suspend fun deleteAllRuns() {
		localRunDataSource.deleteAllRuns()
	}


}