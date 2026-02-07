package com.example.facedetectionapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.facedetectionapp.data.Person
import com.example.facedetectionapp.data.PersonViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonListScreen(
    onAddClick: () -> Unit, // 카메라 화면으로 이동하는 함수(나중에 연결)
    viewModel: PersonViewModel = viewModel() // 아까 만든 뷰모델 자동 연결
) {
    // 뷰모델이 감시하고 있는 사람 목록 데이터를 실시간으로 가져옵니다.
    val people by viewModel.allPeople.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("인맥 관리 비서", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = "사람 추가")
            }
        }
    ) { innerPadding ->
        // 내용물 (리스트)
        if (people.isEmpty()) {
            // 데이터가 없을 때 보여줄 화면
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("아직 등록된 사람이 없습니다.\n+ 버튼을 눌러 얼굴을 등록하세요!", color = Color.Gray)
            }
        } else {
            // 데이터가 있을 때 리스트로 보여줌
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(people) { person ->
                    PersonItem(person)
                }
            }
        }
    }
}

// 리스트의 각 한 줄(카드)을 그리는 함수
@Composable
fun PersonItem(person: Person) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 프로필 아이콘
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.width(16.dp))

            // 이름과 정보
            Column {
                Text(text = person.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (person.company != null || person.jobTitle != null) {
                    Text(
                        text = "${person.company ?: ""} ${person.jobTitle ?: ""}".trim(),
                        fontSize = 14.sp,
                        color = Color.DarkGray
                    )
                }
                if (person.info.isNotBlank()) {
                    Text(
                        text = person.info,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        maxLines = 1
                    )
                }
            }
        }
    }
}