package org.springframework.tests;

import org.springframework.core.io.FileSystemResource;

import java.io.File;

public class Mytest {



	public static void main(String[] args) throws Exception{
		FileSystemResource fileSystemResource = new FileSystemResource(new File("D:/11.txt"));
		fileSystemResource.getInputStream();


	}

}
