package dev.shivamgulia.filebackup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/filebackup_test_repo")
@ActiveProfiles("test")
class FileBackupApplicationTests {

	@Test
	void contextLoads() {
	}

}
