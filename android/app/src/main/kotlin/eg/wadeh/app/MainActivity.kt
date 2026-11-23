package eg.wadeh.app

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import eg.wadeh.app.data.Repository
import eg.wadeh.app.ui.*

class WadehApp : Application() {
    val repository by lazy { Repository(this) }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = (application as WadehApp).repository
        setContent {
            WadehTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "listings") {
                    composable("listings") {
                        val vm: ListingsViewModel = viewModel(factory = factory { ListingsViewModel(repo, it) })
                        ListingsScreen(vm, onOpen = { id -> nav.navigate("listing/$id") }, onToggleLanguage = ::toggleLanguage)
                    }
                    composable(
                        "listing/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                        deepLinks = listOf(navDeepLink { uriPattern = "wadeh://listing/{id}" }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val vm: DetailViewModel = viewModel(key = "detail-$id", factory = factory { DetailViewModel(repo, id) })
                        DetailScreen(vm, onBack = { if (!nav.popBackStack()) nav.navigate("listings") }, onOpen = { other -> nav.navigate("listing/$other") })
                    }
                }
            }
        }
    }

    private fun toggleLanguage() {
        val current = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: resources.configuration.locales[0].language
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (current == "ar") "en" else "ar"))
    }
}

inline fun <reified VM : ViewModel> factory(crossinline make: (SavedStateHandle) -> VM) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = make(extras.createSavedStateHandle()) as T
}
