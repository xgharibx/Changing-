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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState

class WadehApp : Application() {
    val repository by lazy { Repository(this) }
    val saved by lazy { eg.wadeh.app.data.SavedStore(this) }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = (application as WadehApp).repository
        setContent {
            WadehTheme {
                val nav = rememberNavController()
                val store = (application as WadehApp).saved
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                androidx.compose.material3.Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, bottomBar = {
                    if (route?.startsWith("listings") == true || route == "saved") NavigationBar {
                        NavigationBarItem(selected = route?.startsWith("listings") == true, onClick = { nav.navigate("listings?q=") { popUpTo("listings?q={q}") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(Icons.Outlined.Search, null) }, label = { Text(stringResource(R.string.nav_search)) })
                        NavigationBarItem(selected = route == "saved", onClick = { nav.navigate("saved") { popUpTo("listings?q={q}") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(Icons.Outlined.BookmarkBorder, null) }, label = { Text(stringResource(R.string.nav_saved)) }, modifier = Modifier.testTag("nav_saved"))
                    }
                }) { pad ->
                NavHost(nav, startDestination = "listings?q={q}", modifier = Modifier.padding(pad)) {
                    composable("listings?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
                        deepLinks = listOf(navDeepLink { uriPattern = "wadeh://search?q={q}" })) {
                        val vm: ListingsViewModel = viewModel(factory = factory { ListingsViewModel(repo, it) })
                        ListingsScreen(vm, onOpen = { id -> nav.navigate("listing/$id") }, onToggleLanguage = ::toggleLanguage)
                    }
                    composable("saved") {
                        val vm: SavedViewModel = viewModel(factory = factory { SavedViewModel(repo, store) })
                        SavedScreen(vm, onOpen = { id -> nav.navigate("listing/$id") }, onCompare = { ids -> nav.navigate("compare/" + ids.joinToString(",")) })
                    }
                    composable("compare/{ids}", arguments = listOf(navArgument("ids") { type = NavType.StringType })) { entry ->
                        val ids = entry.arguments?.getString("ids").orEmpty().split(',').filter { it.isNotBlank() }
                        val vm: CompareViewModel = viewModel(key = "cmp-$ids", factory = factory { CompareViewModel(repo, store, ids) })
                        CompareScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(
                        "listing/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                        deepLinks = listOf(navDeepLink { uriPattern = "wadeh://listing/{id}" }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val vm: DetailViewModel = viewModel(key = "detail-$id", factory = factory { DetailViewModel(repo, id, store) })
                        DetailScreen(vm, onBack = { if (!nav.popBackStack()) nav.navigate("listings?q=") }, onOpen = { other -> nav.navigate("listing/$other") })
                    }
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
