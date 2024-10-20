package com.dracula.core.domain.run

import com.dracula.core.domain.utils.DataError
import com.dracula.core.domain.utils.EmptyResult
import kotlinx.coroutines.flow.Flow

interface RunRepository {
	fun getRuns(): Flow<List<Run>>
	suspend fun fetchRuns(): EmptyResult<DataError>
	suspend fun upsertRun(run: Run, mapPicture: ByteArray): EmptyResult<DataError>
	suspend fun deleteRun(id: RunId)
}