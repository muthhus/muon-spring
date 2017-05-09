package io.muoncore.newton.command;

import io.muoncore.newton.AggregateRootId;
import io.muoncore.newton.SimpleAggregateRootId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

@ActiveProfiles({"test"})
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {CommandFactoryTest.class, CommandConfiguration.class})
@Configuration
public class CommandFactoryTest {

	@Autowired
	private CommandFactory commandFactory;

	@Test
	public void create() throws Exception {
		Command cmd = commandFactory.create(TestCommand.class);
		Assert.assertNotNull(cmd);
		//THEN - expect no exception
		cmd.execute();
	}

	@Test
	public void createFromPayload() throws Exception {
		Command cmd = commandFactory.create(TestPayloadCommand.class, new TestRequest("AAA"), null);
		Assert.assertNotNull(cmd);
		//THEN - expect no exception
		cmd.execute();
	}

	@Test
	public void createFromCommandDefinition() throws Exception {
		final TestRequest payload = new TestRequest("AAA");
		final AggregateRootId id = new SimpleAggregateRootId();
		final Class<TestPayloadCommand> type = TestPayloadCommand.class;
		Command cmd = commandFactory.create(type, payload, id, "tenantId");
		Assert.assertNotNull(cmd);
		//THEN - expect no exception
		cmd.execute();
	}

	@Test
	public void createFromCommandDefinition_withAdditionalProperties() throws Exception {
		Command cmd = commandFactory.create(TestPayloadCommand.class, null, null, Collections.singletonMap("prop1", "Value"), null);
		Assert.assertNotNull(cmd);
		//THEN - expect no exception
		cmd.execute();
	}

	@Test(expected = IllegalArgumentException.class)
	public void createFromCommandDefinition_withAdditionalProperties_thatsUnkown() throws Exception {
		commandFactory.create(TestPayloadCommand.class, null, null, Collections.singletonMap("propX", "Value"), null);
	}

  @Test
  public void createFromIdUsingReflection() throws Exception {
    final AggregateRootId id = new AggregateRootId("1234");
    Command cmd = commandFactory
      .create(TestCommand.class,null, id,null);
    Assert.assertNotNull(cmd);
    assertEquals(id, ((TestCommand)cmd).getId());
    //THEN - expect no exception
//    cmd.execute();
  }



  //CONFIGURATION
	@Bean
	public TestPayloadCommand testPayloadCommand() {
		return new TestPayloadCommand();
	}

	@Bean
	public TestCommand testCommand() {
		return new TestCommand();
	}

	@Bean
	public TestIdCommand testIdCommand() {
		return new TestIdCommand();
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TestRequest {

		private String prop1;
	}

	public static class TestCommand implements Command {

		protected AggregateRootId id;

		@Override
		public void execute() {
		}

		public void setId(AggregateRootId id) {
			this.id = id;
		}

    public AggregateRootId getId() {
      return id;
    }
  }

	public static class TestIdCommand implements Command {

		protected AggregateRootId id;

		@Override
		public void execute() {
			if (id == null) {
				throw new IllegalStateException("Id must be specified");
			}
		}

		public void setId(AggregateRootId id) {
			this.id = id;
		}
	}


	public static class TestPayloadCommand implements IdentifiableCommand {

    @Setter
		protected AggregateRootId id;
		@Setter
		private String prop1;
		@Setter
		private int prop2 = -1;

		@Override
		public void execute() {
			if (prop1 == null) {
				throw new IllegalStateException("prop1 not specified!");
			}
			if (prop2 != -1) {
				throw new IllegalStateException("prop2 not expected to be specified!");
			}
			System.out.println("RUNNING");
		}

		public void setId(AggregateRootId id) {
			this.id = id;
		}
	}
}
