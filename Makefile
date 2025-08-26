help:
	@echo "Use 'Make <command>' where <command> is one of the following:"
	@echo "test       Run the test suite"
	@echo "deploy-cf  Build and deploy to Cloud Foundry"

test:
	mvn test

deploy-cf:
	mvn clean package
	cf push
