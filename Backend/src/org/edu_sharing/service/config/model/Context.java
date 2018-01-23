package org.edu_sharing.service.config.model;

import java.util.List;

import javax.xml.bind.annotation.XmlElement;

public class Context {
	@XmlElement	public String id;
	@XmlElement	public String[] domain;
	@XmlElement	public Values values;
	@XmlElement	public List<Language> language;
	@XmlElement	public Variables variables;

}
