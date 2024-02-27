package hiconic.devrock.gradle.test.model;

import com.braintribe.model.generic.GenericEntity;
import com.braintribe.model.generic.reflection.EntityType;
import com.braintribe.model.generic.reflection.EntityTypes;

public interface ThisEntity extends GenericEntity {
	EntityType<ThisEntity> T = EntityTypes.T(ThisEntity.class);
}
