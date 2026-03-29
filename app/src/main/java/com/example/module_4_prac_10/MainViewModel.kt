package com.example.module_4_prac_10

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Состояния UI для экрана определения местоположения
 */
sealed class LocationState {
    data object Idle : LocationState()
    data object Loading : LocationState()
    data class Success(
        val address: String,
        val latitude: Double,
        val longitude: Double
    ) : LocationState()
    data class Error(val message: String) : LocationState()
    data object PermissionRequired : LocationState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * FusedLocationProviderClient - рекомендуемый способ получения местоположения в Android.
     * Он объединяет данные из GPS, Wi-Fi и сотовых сетей для получения
     * наиболее точного местоположения с минимальным потреблением батареи.
     */
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val _state = MutableStateFlow<LocationState>(LocationState.Idle)
    val state: StateFlow<LocationState> = _state.asStateFlow()

    /**
     * Проверяет наличие разрешений на доступ к местоположению
     */
    fun hasLocationPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Запрашивает текущее местоположение и выполняет обратное геокодирование
     */
    fun fetchLocation() {
        if (!hasLocationPermission()) {
            _state.value = LocationState.PermissionRequired
            return
        }

        _state.value = LocationState.Loading

        viewModelScope.launch {
            try {
                val location = getCurrentLocation()
                if (location != null) {
                    val address = getAddressFromLocation(location)
                    _state.value = LocationState.Success(
                        address = address,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } else {
                    _state.value = LocationState.Error("Не удалось определить местоположение.\nПроверьте включен ли GPS.")
                }
            } catch (e: SecurityException) {
                _state.value = LocationState.PermissionRequired
            } catch (e: Exception) {
                _state.value = LocationState.Error("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Получает текущее местоположение с помощью FusedLocationProviderClient.
     * Использует getCurrentLocation с высоким приоритетом точности.
     *
     * @return Location или null, если местоположение недоступно
     */
    @Suppress("MissingPermission")
    private suspend fun getCurrentLocation(): Location? {
        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()

            /**
             * getCurrentLocation - получает текущее местоположение устройства.
             * Priority.PRIORITY_HIGH_ACCURACY - использует GPS для максимальной точности.
             * Альтернативы: PRIORITY_BALANCED_POWER_ACCURACY, PRIORITY_LOW_POWER
             */
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                continuation.resume(location)
            }.addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }

            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
        }
    }

    /**
     * Выполняет обратное геокодирование - преобразует координаты в читаемый адрес.
     * Использует Android Geocoder API.
     *
     * @param location Объект Location с координатами
     * @return Строка с адресом
     */
    private suspend fun getAddressFromLocation(location: Location): String {
        return withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            val geocoder = Geocoder(context, Locale.getDefault())

            try {
                /**
                 * Geocoder.getFromLocation - выполняет обратное геокодирование.
                 * Первые два параметра - широта и долгота.
                 * Третий параметр - максимальное количество результатов.
                 *
                 * В Android 13+ доступен асинхронный API с callback,
                 * но для совместимости используем блокирующий вызов в IO-потоке.
                 */
                @Suppress("DEPRECATION")
                val addresses: List<Address>? = geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1
                )

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    buildAddressString(address)
                } else {
                    "Адрес не найден"
                }
            } catch (e: IOException) {
                "Ошибка сети. Проверьте подключение к интернету."
            } catch (e: Exception) {
                "Не удалось определить адрес"
            }
        }
    }

    /**
     * Формирует читаемую строку адреса из объекта Address
     */
    private fun buildAddressString(address: Address): String {
        val parts = mutableListOf<String>()

        // Улица и номер дома
        address.thoroughfare?.let { street ->
            val streetWithNumber = if (address.subThoroughfare != null) {
                "$street, ${address.subThoroughfare}"
            } else {
                street
            }
            parts.add(streetWithNumber)
        }

        // Район/микрорайон
        address.subLocality?.let { parts.add(it) }

        // Город
        address.locality?.let { parts.add(it) }

        // Область/регион
        address.adminArea?.let { parts.add(it) }

        // Страна
        address.countryName?.let { parts.add(it) }

        // Почтовый индекс
        address.postalCode?.let { parts.add("($it)") }

        return if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            address.getAddressLine(0) ?: "Адрес не определён"
        }
    }

    fun reset() {
        _state.value = LocationState.Idle
    }
}
