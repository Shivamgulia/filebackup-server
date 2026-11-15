package dev.shivamgulia.filebackup;

import dev.shivamgulia.filebackup.auth.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class FileBackupApplication {

	public static void main(String[] args) {
		SpringApplication.run(FileBackupApplication.class, args);
	}

}
