pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral(

        )
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "blockchainMania" // 본인 GitHub 아이디 입력
                password = "REMOVED-GITHUB-TOKEN" // 방금 복사한 토큰 붙여넣기
            }
        }




    }
}

rootProject.name = "FaceDetectionAPP"
include(":app")
 