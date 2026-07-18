# Navigation Integration for Extension Settings

## Overview

The extension settings fragments follow the same patterns as the existing
Streamflix settings (mobile uses `Fragment`, TV uses `Fragment` with
`SettingsListStyler`). The project uses **Jetpack Navigation** with XML nav
graphs, but the settings fragments use **manual FragmentTransactions** since
that matches the existing `SettingsMobileFragment` / `SettingsTvFragment`
pattern (which handle their own preference-screen navigation internally).

## Adding to nav_main_graph_mobile.xml

Add these entries to
`/tmp/streamflix/app/src/main/res/navigation/nav_main_graph_mobile.xml`:

```xml
<fragment
    android:id="@+id/extensions_mobile"
    android:name="com.streamflixreborn.streamflix.extensions.settings.ExtensionsMobileFragment"
    android:label="fragment_extensions_mobile"
    tools:layout="@layout/fragment_extensions_mobile" />

<fragment
    android:id="@+id/repositories_mobile"
    android:name="com.streamflixreborn.streamflix.extensions.settings.RepositoriesMobileFragment"
    android:label="fragment_repositories_mobile"
    tools:layout="@layout/fragment_repositories_mobile" />
```

## Adding to nav_main_graph_tv.xml

Add these entries to
`/tmp/streamflix/app/src/main/res/navigation/nav_main_graph_tv.xml`:

```xml
<fragment
    android:id="@+id/extensions_tv"
    android:name="com.streamflixreborn.streamflix.extensions.settings.ExtensionsTvFragment"
    android:label="fragment_extensions_tv"
    tools:layout="@layout/fragment_extensions_tv" />

<fragment
    android:id="@+id/repositories_tv"
    android:name="com.streamflixreborn.streamflix.extensions.settings.RepositoriesTvFragment"
    android:label="fragment_repositories_tv"
    tools:layout="@layout/fragment_repositories_tv" />
```

## Wiring into Existing Settings Fragments

### SettingsMobileFragment

In `displaySettings()` (around line 830), add after the existing preference
setup:

```kotlin
// In SettingsMobileFragment.displaySettings():
findPreference<PreferenceScreen>("screen_extensions")?.apply {
    title = "Extensions"
    summary = "${ExtensionRegistry.getProviderCount()} provider(s) loaded"
    setOnPreferenceClickListener {
        parentFragmentManager.commit {
            replace(R.id.container, ExtensionsMobileFragment())
            addToBackStack(null)
        }
        true
    }
}
```

Also add the preference screen entry in `settings_mobile.xml`:

```xml
<PreferenceScreen
    android:key="screen_extensions"
    android:title="Extensions"
    android:summary="Manage extension providers">
</PreferenceScreen>
```

### SettingsTvFragment

In `displaySettings()` (around line 888), add after the existing preference
setup:

```kotlin
// In SettingsTvFragment.displaySettings():
findPreference<PreferenceScreen>("screen_extensions")?.apply {
    title = "Extensions"
    summary = "${ExtensionRegistry.getProviderCount()} provider(s) loaded"
    setOnPreferenceClickListener {
        parentFragmentManager.commit {
            replace(android.R.id.content, ExtensionsTvFragment())
            addToBackStack(null)
        }
        true
    }
}
```

Also add the preference screen entry in `settings_tv.xml`:

```xml
<PreferenceScreen
    android:key="screen_extensions"
    android:title="Extensions"
    android:summary="Manage extension providers">
</PreferenceScreen>
```

## Fragment-to-Fragment Navigation (within Extension Settings)

The mobile fragments navigate between each other using FragmentTransactions:

- `ExtensionsMobileFragment` → "Browse Repositories" button →
  `RepositoriesMobileFragment`
- `ExtensionsMobileFragment` → back button → previous screen
- `RepositoriesMobileFragment` → back button → `ExtensionsMobileFragment`

The TV fragments follow the same pattern using `android.R.id.content` as the
container (matching the existing SettingsTvFragment pattern).

## Container IDs

| Device | Container ID           |
| ------ | ---------------------- |
| Mobile | `R.id.container`       |
| TV     | `android.R.id.content` |

These match the existing patterns in `SettingsMobileFragment` and
`SettingsTvFragment` respectively.

## Back Navigation

Both `ExtensionsMobileFragment` and `RepositoriesMobileFragment` use
`addToBackStack(null)` so the system back button returns to the previous
fragment. No custom `OnBackPressedCallback` is needed — the `FragmentManager`
handles it automatically.
