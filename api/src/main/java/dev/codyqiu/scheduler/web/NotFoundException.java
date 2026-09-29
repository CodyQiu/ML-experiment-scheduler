package dev.codyqiu.scheduler.web;

public class NotFoundException extends RuntimeException {

	public NotFoundException(String resource, long id) {
		super("%s %d does not exist".formatted(resource, id));
	}

}
