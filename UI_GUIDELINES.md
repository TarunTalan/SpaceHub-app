# SpaceHub App - UI Design System

## Overview
This document outlines the UI design system for SpaceHub to ensure consistency across all screens.

---

## Color Palette

### Primary Colors
- **Primary Blue**: `#2563EB` (`@color/primary_blue`)
- **Primary Dark**: `#1E40AF` (`@color/primary_dark`)

### Grayscale
- **White**: `#FFFFFF` (`@color/white` or `@color/text_primary`)
- **Gray Dark**: `#475569` (`@color/gray_dark`)
- **Gray Medium**: `#ADADAD` (`@color/gray_medium`)
- **Gray Light**: `#D3D3D3` (`@color/gray_light`)
- **Gray Subtle**: `#E3E6E9` (`@color/gray_subtle`)

### Text Colors
- **Primary Text**: `#FFFFFF` (`@color/text_primary`)
- **Secondary Text**: `#C6CAD1` (`@color/text_secondary`)
- **Tertiary Text**: `#BFBFBF` (`@color/text_tertiary`)
- **Hint Text**: `#475569` (`@color/hint_color`)

### Semantic Colors
- **Error Red**: `#ED2828` (`@color/error_red`)
- **Error Text**: `#F44336` (`@color/error_text`)
- **Success Green**: `#10B981` (`@color/success_green`)

### Background Colors
- **Background Primary**: `#16151A` (`@color/background_primary`)
- **Background Secondary**: `#1D2532` (`@color/background_secondary`)
- **Surface**: `#20262F` (`@color/surface`)

---

## Typography

### Text Sizes
- **Display**: `32sp` (`@dimen/text_size_display`)
- **Headline**: `28sp` (`@dimen/text_size_headline`)
- **Title**: `25sp` (`@dimen/title_text_size`)
- **Subtitle**: `14sp` (`@dimen/subtitle_text_size`)
- **Body**: `14sp` (`@dimen/text_size_body`)
- **Caption**: `12sp` (`@dimen/text_size_caption`)
- **Small**: `11sp` (`@dimen/text_size_small`)

### Font Family
- **Regular**: `roboto_regular`
- **Medium**: `roboto_medium`
- **Semibold**: `roboto_semibold`
- **Bold**: `roboto_bold`

### Text Styles
```xml
<!-- Title -->
<TextView
    style="@style/TextAppearance.App.Title"
    android:text="Your Title" />

<!-- Subtitle -->
<TextView
    style="@style/TextAppearance.App.Subtitle"
    android:text="Your Subtitle" />

<!-- Label -->
<TextView
    style="@style/TextAppearance.App.Label"
    android:text="Your Label" />

<!-- Body -->
<TextView
    style="@style/TextAppearance.App.Body"
    android:text="Your Body Text" />
```

---

## Spacing

### Standard Spacing Scale
- **Tiny**: `4dp` (`@dimen/spacing_tiny`)
- **Small**: `8dp` (`@dimen/spacing_small`)
- **Medium**: `12dp` (`@dimen/spacing_medium`)
- **Large**: `16dp` (`@dimen/spacing_large`)
- **XLarge**: `24dp` (`@dimen/spacing_xlarge`)
- **XXLarge**: `32dp` (`@dimen/spacing_xxlarge`)

### Screen Margins
- **Horizontal**: `16dp` (`@dimen/screen_margin_horizontal`)
- **Vertical**: `16dp` (`@dimen/screen_margin_vertical`)

### Guidelines
Use ConstraintLayout guidelines at:
- **Start**: `0.08` (8%)
- **End**: `0.92` (92%)

---

## Components

### Input Fields

#### TextInputLayout Style
```xml
<com.google.android.material.textfield.TextInputLayout
    style="@style/Widget.App.TextInputLayout"
    android:layout_width="0dp"
    app:startIconDrawable="@drawable/your_icon"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent">
    
    <com.google.android.material.textfield.TextInputEditText
        style="@style/AppInputEditText"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:hint="@string/your_hint"
        android:inputType="text" />
</com.google.android.material.textfield.TextInputLayout>
```

#### Plain EditText Style
```xml
<EditText
    style="@style/Widget.App.EditText"
    android:layout_width="0dp"
    android:hint="@string/your_hint"
    android:inputType="text" />
```

#### Input Dimensions
- **Height**: `36dp` (default) / `37dp` (h680dp) / `39dp` (h800dp)
- **Corner Radius**: `24dp` (default) / `22dp` (h680dp) / `28dp` (h800dp)
- **Max Width**: `100dp` (default) / `380dp` (h680dp) / `520dp` (h800dp)

### Buttons

#### Primary Button
```xml
<androidx.appcompat.widget.AppCompatButton
    style="@style/Widget.App.Button.Primary"
    android:layout_width="0dp"
    android:text="@string/button_text" />
```

#### Secondary Button
```xml
<androidx.appcompat.widget.AppCompatButton
    style="@style/Widget.App.Button.Secondary"
    android:layout_width="0dp"
    android:text="@string/button_text" />
```

#### Outlined Button
```xml
<androidx.appcompat.widget.AppCompatButton
    style="@style/Widget.App.Button.Outlined"
    android:layout_width="0dp"
    android:text="@string/button_text" />
```

#### Text Button
```xml
<TextView
    style="@style/Widget.App.Button.Text"
    android:text="@string/button_text"
    android:clickable="true"
    android:focusable="true" />
```

#### Button Dimensions
- **Height**: `32dp` (default) / `37dp` (h680dp) / `39dp` (h800dp)
- **Medium Height**: `36dp` / `40dp` / `42dp`
- **Large Height**: `42dp` / `44dp` / `46dp`
- **Corner Radius**: `24dp` (`@dimen/button_corner_radius`)

### Cards
```xml
<com.google.android.material.card.MaterialCardView
    style="@style/Widget.App.Card"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
    
    <!-- Card content -->
    
</com.google.android.material.card.MaterialCardView>
```

### Toolbar
```xml
<androidx.constraintlayout.widget.ConstraintLayout
    style="@style/Widget.App.Toolbar"
    android:layout_width="match_parent">
    
    <!-- Toolbar content -->
    
</androidx.constraintlayout.widget.ConstraintLayout>
```

---

## Icons

### Icon Sizes
- **Small**: `16dp` (`@dimen/icon_size_small`)
- **Medium**: `24dp` (`@dimen/icon_size_medium`)
- **Large**: `32dp` (`@dimen/icon_size_large`)
- **XLarge**: `48dp` (`@dimen/icon_size_xlarge`)

### Avatar Sizes
- **Small**: `32dp` (`@dimen/avatar_size_small`)
- **Medium**: `48dp` (`@dimen/avatar_size_medium`)
- **Large**: `64dp` (`@dimen/avatar_size_large`)
- **XLarge**: `96dp` (`@dimen/avatar_size_xlarge`)

---

## Elevations

- **None**: `0dp` (`@dimen/elevation_none`)
- **Low**: `2dp` (`@dimen/elevation_low`)
- **Medium**: `4dp` (`@dimen/elevation_medium`)
- **High**: `8dp` (`@dimen/elevation_high`)

---

## Layout Patterns

### Auth Screen Layout
```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/background_gradient">

    <!-- Content Layout -->
    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/contentLayout"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/margin_title_top"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="@+id/guideline_start"
        app:layout_constraintEnd_toStartOf="@+id/guideline_end">

        <!-- Title Group -->
        <!-- Input Container -->
        <!-- Actions Group -->
        
    </androidx.constraintlayout.widget.ConstraintLayout>

    <!-- Guidelines -->
    <androidx.constraintlayout.widget.Guideline
        android:id="@+id/guideline_start"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        app:layout_constraintGuide_percent="0.08" />

    <androidx.constraintlayout.widget.Guideline
        android:id="@+id/guideline_end"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        app:layout_constraintGuide_percent="0.92" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

---

## Best Practices

### DO's ✓
- Always use color resources from `colors.xml`
- Always use dimension resources from `dimens.xml`
- Use style references for consistent component styling
- Use ConstraintLayout for responsive layouts
- Use guidelines for consistent margins (8% and 92%)
- Use semantic color names (e.g., `@color/text_primary` instead of `@android:color/white`)
- Group related UI elements in nested ConstraintLayouts
- Use descriptive IDs with consistent naming (e.g., `tvTitle`, `etEmail`, `btnSubmit`)

### DON'Ts ✗
- Don't hardcode colors (e.g., `#FFFFFF`)
- Don't hardcode dimensions (e.g., `16dp`)
- Don't mix `@android:color/white` and `@color/white` - use `@color/text_primary` consistently
- Don't use different padding/margin values for similar components
- Don't create custom styles when standard ones exist
- Don't skip using the established spacing scale

---

## Responsive Design

The app uses height-based breakpoints:

### Default (< 680dp height)
- Smallest devices
- Compact spacing
- Smaller text sizes

### Medium (680dp - 799dp height)
- `values-h680dp/dimens.xml`
- Medium spacing
- Medium text sizes

### Large (>= 800dp height)
- `values-h800dp/dimens.xml`
- Comfortable spacing
- Larger text sizes

---

## Error States

### Error Text Color
Always use `@color/error_text` for error messages.

### Error Display Pattern
```xml
<TextView
    android:id="@+id/tvError"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/error_margin_top"
    android:fontFamily="@font/roboto_regular"
    android:textColor="@color/error_text"
    android:textSize="@dimen/error_text_size"
    android:visibility="invisible"
    tools:text="Error message" />
```

---

## Loading States

Use the BaseFragment loader for consistent loading indicators:
```kotlin
showLoader() // Show loading
hideLoader() // Hide loading
```

Loader size: `48dp` (`@dimen/loader_size`)

---

## Accessibility

- Always provide `contentDescription` for images and icons
- Use minimum touch target size of `48dp`
- Ensure sufficient color contrast (4.5:1 for text)
- Support text scaling

---

## Version
Document Version: 1.0
Last Updated: December 2025

