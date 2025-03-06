import sh.fyz.architect.repositories.GenericRelayRepository;

import java.util.UUID;

public class RankRepository extends GenericRelayRepository<Rank> {

    public RankRepository() {
        super(Rank.class);
    }

}
