package com.example.module_4_prac_10

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
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

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val _state = MutableStateFlow<LocationState>(LocationState.Idle)
    val state: StateFlow<LocationState> = _state.asStateFlow()

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

    @Suppress("MissingPermission")
    private suspend fun getCurrentLocation(): Location? {
        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()

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

    private suspend fun getAddressFromLocation(location: Location): String {
        return withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            val geocoder = Geocoder(context, Locale.getDefault())

            try {
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

    private fun buildAddressString(address: Address): String {
        val parts = mutableListOf<String>()

        address.thoroughfare?.let { street ->
            val streetWithNumber = if (address.subThoroughfare != null) {
                "$street, ${address.subThoroughfare}"
            } else {
                street
            }
            parts.add(streetWithNumber)
        }

        address.subLocality?.let { parts.add(it) }

        address.locality?.let { parts.add(it) }

        address.adminArea?.let { parts.add(it) }

        address.countryName?.let { parts.add(it) }

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
