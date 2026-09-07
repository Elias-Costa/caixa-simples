package br.com.caixasimples;

import org.springframework.boot.SpringApplication;

public class TestCaixaSimplesApplication {

	public static void main(String[] args) {
		SpringApplication.from(CaixaSimplesApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
