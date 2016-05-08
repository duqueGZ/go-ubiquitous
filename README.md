Udacity Android Developer Nanodegree. Project 6

In order to make the app works correctly is needed to configure and use a openweathermap.org API KEY.
To get one, you must to sign up for an account in their webpage and then look for your API KEY through your profile page.
Since it is not allowed to publicly share your personal API KEY, the code in this repository does not contain mine. So, 
once you have the API KEY, it would be needed to replace *'MyOpenWeatherMapApiKey'* placeholder in app *build.gradle* file by your real and valid API KEY value:

app/build.gradle:
```gradle
apply plugin: 'com.android.application'
apply plugin: 'com.google.gms.google-services'

android {
    ...
    buildTypes.each {
        it.buildConfigField 'String', 'OPEN_WEATHER_MAP_API_KEY', '\"MyOpenWeatherMapApiKey\"'
    }
}
...
```

This project has been developed taking as a starting point the Advanced Android Sample App (Sunshine app): https://github.com/udacity/Advanced_Android_Development/tree/7.05_Pretty_Wallpaper_Time

Advanced Android Sample App
===================================

Synchronizes weather information from OpenWeatherMap on Android Phones and Tablets. Used in the Udacity Advanced Android course.

Pre-requisites
--------------
Android SDK 21 or Higher
Build Tools version 21.1.2
Android Support AppCompat 22.2.0
Android Support Annotations 22.2.0
Android Support GridLayout 22.2.0
Android Support CardView 22.2.0
Android Support Design 22.2.0
Android Support RecyclerView 22.2.0
Google Play Services GCM 7.0.0
BumpTech Glide 3.5.2


Getting Started
---------------
This sample uses the Gradle build system.  To build this project, use the
"gradlew build" command or use "Import Project" in Android Studio.

Support
-------

- Google+ Community: https://plus.google.com/communities/105153134372062985968
- Stack Overflow: http://stackoverflow.com/questions/tagged/android

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub. Please see CONTRIBUTING.md for more details.

License
-------
Copyright 2015 The Android Open Source Project, Inc.

Licensed to the Apache Software Foundation (ASF) under one or more contributor
license agreements.  See the NOTICE file distributed with this work for
additional information regarding copyright ownership.  The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License.  You may obtain a copy of
the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.

