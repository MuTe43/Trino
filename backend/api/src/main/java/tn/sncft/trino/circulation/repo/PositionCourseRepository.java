package tn.sncft.trino.circulation.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.circulation.domaine.PositionCourse;

public interface PositionCourseRepository extends JpaRepository<PositionCourse, Long> {
}
