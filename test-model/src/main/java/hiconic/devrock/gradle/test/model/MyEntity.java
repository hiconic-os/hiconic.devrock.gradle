package hiconic.devrock.gradle.test.model;

import com.braintribe.model.generic.GenericEntity;
import com.braintribe.model.generic.reflection.EntityType;
import com.braintribe.model.generic.reflection.EntityTypes;

public interface MyEntity extends GenericEntity {
	EntityType<MyEntity> T = EntityTypes.T(MyEntity.class);
}
